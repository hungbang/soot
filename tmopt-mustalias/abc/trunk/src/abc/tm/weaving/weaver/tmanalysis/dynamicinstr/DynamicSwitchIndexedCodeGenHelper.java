package abc.tm.weaving.weaver.tmanalysis.dynamicinstr;

import java.util.Collections;

import soot.Body;
import soot.BooleanType;
import soot.Local;
import soot.Modifier;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootField;
import soot.SootFieldRef;
import soot.SootMethod;
import soot.SootMethodRef;
import soot.Type;
import soot.VoidType;
import soot.javaToJimple.LocalGenerator;
import soot.jimple.AssignStmt;
import soot.jimple.EqExpr;
import soot.jimple.IfStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.NopStmt;
import soot.jimple.NullConstant;
import soot.util.Chain;
import abc.tm.weaving.aspectinfo.IndexingScheme;
import abc.tm.weaving.aspectinfo.TraceMatch;
import abc.tm.weaving.matching.SMNode;
import abc.tm.weaving.weaver.IndexedCodeGenHelper;

/**
 * Version of {@link IndexedCodeGenHelper} which allows for dynamic switching of non-skip shadows.
 *
 * @author Eric Bodden
 */
public class DynamicSwitchIndexedCodeGenHelper extends IndexedCodeGenHelper {

	/** name of the boolean field that is used to state whether label shadows are enabled or not */
	private static final String BOOLEAN_FIELD_NAME = "labelShadowsEnabled";
		
	/** name of the method to enable non-skip shadows */
	private static final String ENABLE_METHOD_NAME = "enableLabelShadows";
	
	/** name of the method to disablenon-skip shadows */
	private static final String DISABLE_METHOD_NAME = "disableLabelShadows";

	/**
	 * @param tm tracematch to generate code for
	 */
	public DynamicSwitchIndexedCodeGenHelper(TraceMatch tm) {
		super(tm);
	}
	
    /**
     * Generate code to update a label with the constraint
     * for performing a "from --->[symbol] to" transition.
     * 
     * Does the same as {@link IndexedCodeGenHelper#genLabelUpdate(int, int, String, SootMethod)}
     * but wraps the code generated by this method into a check on the boolean field.
     */
    public void genLabelUpdate(int from, int to, String symbol,
                                        SootMethod method)
    {
        
        Body body = method.getActiveBody();
        Chain units = body.getUnits();
        LocalGenerator localGen = new LocalGenerator(body);
        
        //create new nop-statement as jump target
        NopStmt jumpTarget = Jimple.v().newNopStmt();
        
        //generate field, if not already present
        genSwitchField(method.getDeclaringClass());
        //method to set field to true, if not already present
        genEnableMethod(method.getDeclaringClass());
        //method to set field to false, if not already present
        genDisableMethod(method.getDeclaringClass());
        
        //create branch if(!labelShadowsEnabled) goto jumpTarget
        Chain branchUnits = newChain();
        //lse = labelShadowsEnabled;
        InstanceFieldRef booleanFieldRef = Jimple.v().newInstanceFieldRef(
        		body.getThisLocal(),
        		Scene.v().makeFieldRef(method.getDeclaringClass(), BOOLEAN_FIELD_NAME, BooleanType.v(), false)
        );
        Local booleanLocal = localGen.generateLocal(BooleanType.v());
        AssignStmt fieldAssignStmt = Jimple.v().newAssignStmt(booleanLocal, booleanFieldRef);
        branchUnits.add(fieldAssignStmt);
        //if(lse==0) goto jumpTarget
        IfStmt ifStmt = Jimple.v().newIfStmt(Jimple.v().newEqExpr(booleanLocal, IntConstant.v(0)), jumpTarget);
        branchUnits.add(ifStmt);
		insertBeforeReturn(branchUnits, units);        
        
        super.genLabelUpdate(from, to, symbol, method);

        //insert new nop-statement right before the return statement
        Chain nopUnitChain = newChain();
		nopUnitChain.add(jumpTarget);
		insertBeforeReturn(nopUnitChain, units);
		
    }
    
    
    /**
     * Generate code that before executing the skip-labe update first checks whether there
     * currently exists a disjunct at all that can be eliminated by the skip-loop.
     * This optimization is automatically only applied at states which are known to bind
     * all variables.
     * 
     * The code that is wrapped that way is the same as generated by
     * {@link IndexedCodeGenHelper#genSkipLabelUpdate(SMNode, String, SootMethod)}.
     */
    public void genSkipLabelUpdate(SMNode state, String symbol, SootMethod method) {
        
    	//if not all variables are bound on this state, just proceed with super-call  
		if(!state.boundVars.containsAll(tm.getFormalNames())) {
			super.genSkipLabelUpdate(state, symbol, method);
			return;
		}
    	
    	//else, apply the optimization
    	
    	Body body = method.getActiveBody();
        LocalGenerator localGen = new LocalGenerator(body);
        
        Chain newUnits = newChain();
        
        //create new nop-statement as jump target
        NopStmt jumpTarget = Jimple.v().newNopStmt();

        //Constraint c = this.tracematch$i_labelj;
        Local thisLocal = body.getThisLocal();
        Local constraintLocal = getLabel(body, newUnits, thisLocal, state.getNumber(), LABEL);
        
        //Set/Map $temp = c.<disjunctsFieldName>

        //field name and type of disjuncts set/map depends on whether or not the state uses indexing
        
        IndexingScheme scheme = tm.getIndexingScheme();
        
        //FIXME does not compile any more
        boolean stateUsesIndexing = scheme.getStructure(state).height()>0;
        
        String disjunctsFieldName = stateUsesIndexing ? "indexedDisjuncts" : "disjuncts";
        Type disjunctsFieldType = stateUsesIndexing ? RefType.v("java.util.Map") : set.getType();
        
        SootFieldRef disjunctsFieldRef = Scene.v().makeFieldRef(constraint, disjunctsFieldName, disjunctsFieldType, false);
        InstanceFieldRef rhs = Jimple.v().newInstanceFieldRef(constraintLocal, disjunctsFieldRef);
        Local disjunctsLocal = localGen.generateLocal(disjunctsFieldType);
        newUnits.add(Jimple.v().newAssignStmt(disjunctsLocal, rhs));
        
        //if($temp==null) goto jumpTarget
        EqExpr nullCheck = Jimple.v().newEqExpr(disjunctsLocal, NullConstant.v());
        newUnits.add(Jimple.v().newIfStmt(nullCheck, jumpTarget));
        
        //boolean $isEmpty = $temp.isEmpty();
        SootClass disjunctsFieldClass = stateUsesIndexing ? Scene.v().getSootClass("java.util.Map") : Scene.v().getSootClass("java.util.Set");
        Local isEmptyLocal = localGen.generateLocal(BooleanType.v());
        SootMethodRef isEmptyMethodRef = Scene.v().makeMethodRef(disjunctsFieldClass, "isEmpty", Collections.EMPTY_LIST, BooleanType.v(), false);
        InvokeExpr isEmptyInvokeExpr = Jimple.v().newInterfaceInvokeExpr(disjunctsLocal, isEmptyMethodRef);
        newUnits.add(Jimple.v().newAssignStmt(isEmptyLocal, isEmptyInvokeExpr));
        
        //if($isEmpty) goto jumpTarget
        EqExpr emptyCheck = Jimple.v().newEqExpr(isEmptyLocal, IntConstant.v(1));
        newUnits.add(Jimple.v().newIfStmt(emptyCheck, jumpTarget));

        //generate code we would generate otherwise
        Chain units = body.getUnits();
        insertBeforeReturn(newUnits, units);
        super.genSkipLabelUpdate(state, symbol, method);
        
        //insert jump target
        newUnits = newChain();
        newUnits.add(jumpTarget);        
        insertBeforeReturn(newUnits, units);        
    }
    
    
    /**
     * Generates the boolean field, if not yet present.
     * @param container the containing class
     */
    protected void genSwitchField(SootClass container) {
    	if(!container.declaresFieldByName(BOOLEAN_FIELD_NAME)) {
    		//add field
    		container.addField(new SootField(BOOLEAN_FIELD_NAME, BooleanType.v(), Modifier.PUBLIC));

    		//initialize to "true" in constructor
    		SootMethod initializer = container.getMethodByName(SootMethod.constructorName);
    		Body initBody = initializer.getActiveBody();    		    		
	        InstanceFieldRef booleanFieldRef = Jimple.v().newInstanceFieldRef(
	        		initBody.getThisLocal(),
	        		Scene.v().makeFieldRef(container, BOOLEAN_FIELD_NAME, BooleanType.v(), false)
	        );
	        AssignStmt fieldAssignStmt = Jimple.v().newAssignStmt(booleanFieldRef,IntConstant.v(1));
	        Chain newUnits = newChain();
	        newUnits.addFirst(fieldAssignStmt);
	        insertBeforeReturn(newUnits, initBody.getUnits());
    	}    	
    }
    
    /**
     * generates the method for enabling non-skip shadows, if not already present
     * @param container the containing class
     */
    protected void genEnableMethod(SootClass container) {
    	genFieldSwitchMethod(ENABLE_METHOD_NAME, true, container);
    }

    /**
     * generates the method for disabling non-skip shadows, if not already present
     * @param container the containing class
     */
    protected void genDisableMethod(SootClass container) {
    	genFieldSwitchMethod(DISABLE_METHOD_NAME, false, container);
    }

    /**
     * FIXME: This only works if there is at most one tracematch per Class.
     * Actually we need to generate 'enable/disable$tracematch$i'-methods
	 * @param methodName
	 * @param enable 
     * @param container 
	 */
	private void genFieldSwitchMethod(String methodName, boolean enable, SootClass container) {
		if(!container.declaresMethodByName(methodName)) {
    		SootMethod method = new SootMethod(methodName,Collections.EMPTY_LIST,VoidType.v(),Modifier.PUBLIC);
			container.addMethod(method);			
			JimpleBody body = Jimple.v().newBody(method);
			method.setActiveBody(body);
			Chain units = body.getUnits();
			
			
			//add identity statement; necessary for locking
			Local thisLocal = Jimple.v().newLocal("this", container.getType());			
			body.getLocals().add(thisLocal);
			units.add(Jimple.v().newIdentityStmt(thisLocal, Jimple.v().newThisRef(container.getType())));			
			//acquire tracematch lock
			getLock(body, units);
			
			//set value
	        InstanceFieldRef booleanFieldRef = Jimple.v().newInstanceFieldRef(
	        		body.getThisLocal(),
	        		Scene.v().makeFieldRef(method.getDeclaringClass(), BOOLEAN_FIELD_NAME, BooleanType.v(), false)
	        );
	        AssignStmt fieldAssignStmt = Jimple.v().newAssignStmt(booleanFieldRef,IntConstant.v(enable?1:0));
	        units.add(fieldAssignStmt);

	        //release lock
	        releaseLock(body, units);

	        units.add(Jimple.v().newReturnVoidStmt());
		}
	}
	
}
