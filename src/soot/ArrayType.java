/* Soot - a J*va Optimization Framework
 * Copyright (C) 1997-1999 Raja Vallee-Rai
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

/*
 * Modified by the Sable Research Group and others 1997-1999.  
 * See the 'credits' file distributed with Soot for the complete list of
 * contributors.  (Soot is distributed at http://www.sable.mcgill.ca/soot)
 */


package soot;

import soot.util.*;
import java.util.*;


/**
 *   A class that models Java's array types. ArrayTypes are parametrized by a Type and 
 *   and an integer representing the array's dimension count..
 *   Two ArrayType are 'equal' if they are parametrized equally.
 *
 */
public class ArrayType extends RefLikeType
{
    /** baseType can be any type except for an array type, null and void 
     */
    public final Type baseType;
    
    /** dimension count for the array type*/
    public final int numDimensions;

   
    private ArrayType(Type baseType, int numDimensions)
    {
        if( !( baseType instanceof PrimType || baseType instanceof RefType ) )
            throw new RuntimeException( "oops" );
        this.baseType = baseType;
        this.numDimensions = numDimensions;
    }

     /** 
     *  Creates an ArrayType  parametrized by a given Type and dimension count.
     *  @param baseType a Type to parametrize the ArrayType
     *  @param numDimensions the dimension count to parametrize the ArrayType.
     *  @return an ArrayType parametrized accrodingly.
     */
    public static ArrayType v(Type baseType, int numDimensions)
    {
        ArrayType ret;
        Type elementType;
        if( numDimensions == 1 ) {
            elementType = baseType;
        } else {
            elementType = ArrayType.v( baseType, numDimensions-1 );
        }
        ret = elementType.getArrayType();
        if( ret == null ) {
            ret = new ArrayType(baseType, numDimensions);
            elementType.setArrayType( ret );
        }
        return ret;
    }

    /**
     *  Two ArrayType are 'equal' if they are parametrized identically.
     * (ie have same Type and dimension count.
     * @param t object to test for equality
     * @return true if t is an ArrayType and is parametrized identically to this.
     */
    public boolean equals(Object t)
    {
        return t == this;
        /*
        if(t instanceof ArrayType)
        {
            ArrayType arrayType = (ArrayType) t;

            return this.numDimensions == arrayType.numDimensions &&
                this.baseType.equals(arrayType.baseType);
        }
        else
            return false;
            */
    }

    public String toBriefString()
    {
        StringBuffer buffer = new StringBuffer();

        buffer.append(baseType.toBriefString());

        for(int i = 0; i < numDimensions; i++)
            buffer.append("[]");

        return buffer.toString();
    }

    public String toString()
    {
        StringBuffer buffer = new StringBuffer();

        buffer.append(baseType.toString());

        for(int i = 0; i < numDimensions; i++)
            buffer.append("[]");

        return buffer.toString();
    }

    public int hashCode()
    {
        return baseType.hashCode() + 0x432E0341 * numDimensions;
    }

    public void apply(Switch sw)
    {
        ((TypeSwitch) sw).caseArrayType(this);
    }

    public Type getArrayElementType() {
	return getElementType();
    }
    public Type getElementType() {
	if( numDimensions > 1 ) {
	    return ArrayType.v( baseType, numDimensions-1 );
	} else {
	    return baseType;
	}
    }
    public ArrayType makeArrayType() {
        return ArrayType.v( baseType, numDimensions+1 );
    }
}

