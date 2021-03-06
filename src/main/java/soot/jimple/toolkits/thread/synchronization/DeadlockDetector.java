package soot.jimple.toolkits.thread.synchronization;

/*-
 * #%L
 * Soot - a J*va Optimization Framework
 * %%
 * Copyright (C) 1997 - 2018 Raja Vallée-Rai and others
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.EquivalentValue;
import soot.MethodOrMethodContext;
import soot.Scene;
import soot.Unit;
import soot.Value;
import soot.jimple.spark.pag.PAG;
import soot.jimple.spark.sets.HashPointsToSet;
import soot.jimple.spark.sets.PointsToSetInternal;
import soot.jimple.toolkits.callgraph.Filter;
import soot.jimple.toolkits.callgraph.TransitiveTargets;
import soot.toolkits.graph.DirectedGraph;
import soot.toolkits.graph.HashMutableDirectedGraph;
import soot.toolkits.graph.HashMutableEdgeLabelledDirectedGraph;
import soot.toolkits.graph.MutableDirectedGraph;
import soot.toolkits.graph.MutableEdgeLabelledDirectedGraph;

public class DeadlockDetector {
  private static final Logger logger = LoggerFactory.getLogger(DeadlockDetector.class);

  boolean optionPrintDebug;
  boolean optionRepairDeadlock;
  boolean optionAllowSelfEdges;
  List<CriticalSection> criticalSections;
  TransitiveTargets tt;

  public DeadlockDetector(boolean optionPrintDebug, boolean optionRepairDeadlock, boolean optionAllowSelfEdges,
      List<CriticalSection> criticalSections) {
    this.optionPrintDebug = optionPrintDebug;
    this.optionRepairDeadlock = optionRepairDeadlock;
    this.optionAllowSelfEdges = optionAllowSelfEdges && !optionRepairDeadlock; // can only do this if not repairing
    this.criticalSections = criticalSections;
    this.tt = new TransitiveTargets(Scene.v().getCallGraph(), new Filter(new CriticalSectionVisibleEdgesPred(null)));
  }

  public MutableDirectedGraph<CriticalSectionGroup> detectComponentBasedDeadlock() {
    MutableDirectedGraph<CriticalSectionGroup> lockOrder;
    boolean foundDeadlock;
    int iteration = 0;
    do {
      iteration++;
      logger.debug("[DeadlockDetector] Deadlock Iteration #" + iteration);
      foundDeadlock = false;
      lockOrder = new HashMutableDirectedGraph<CriticalSectionGroup>(); // start each iteration with a fresh graph

      // Assemble the partial ordering of locks
      Iterator<CriticalSection> deadlockIt1 = criticalSections.iterator();
      while (deadlockIt1.hasNext() && !foundDeadlock) {
        CriticalSection tn1 = deadlockIt1.next();

        // skip if unlocked
        if (tn1.setNumber <= 0) {
          continue;
        }

        // add a node for this set
        if (!lockOrder.containsNode(tn1.group)) {
          lockOrder.addNode(tn1.group);
        }

        // Get list of tn1's target methods
        if (tn1.transitiveTargets == null) {
          tn1.transitiveTargets = new HashSet<MethodOrMethodContext>();
          for (Unit tn1Invoke : tn1.invokes) {
            Iterator<MethodOrMethodContext> targetIt = tt.iterator(tn1Invoke);
            while (targetIt.hasNext()) {
              tn1.transitiveTargets.add(targetIt.next());
            }
          }
        }

        // compare to each other tn
        Iterator<CriticalSection> deadlockIt2 = criticalSections.iterator();
        while (deadlockIt2.hasNext() && (!optionRepairDeadlock || !foundDeadlock)) {
          CriticalSection tn2 = deadlockIt2.next();

          // skip if unlocked or in same set as tn1
          if (tn2.setNumber <= 0 || (tn2.setNumber == tn1.setNumber && !optionAllowSelfEdges)) // this is wrong... dynamic
                                                                                               // locks in same group can be
                                                                                               // diff locks
          {
            continue;
          }

          // add a node for this set
          if (!lockOrder.containsNode(tn2.group)) {
            lockOrder.addNode(tn2.group);
          }

          if (tn1.transitiveTargets.contains(tn2.method)) {
            // This implies the partial ordering tn1lock before tn2lock
            if (optionPrintDebug) {
              logger.debug("group" + (tn1.setNumber) + " before group" + (tn2.setNumber) + ": " + "outer: " + tn1.name
                  + " inner: " + tn2.name);
            }

            // Check if tn2lock before tn1lock is in our lock order
            List<CriticalSectionGroup> afterTn2 = new ArrayList<CriticalSectionGroup>();
            afterTn2.addAll(lockOrder.getSuccsOf(tn2.group));
            for (int i = 0; i < afterTn2.size(); i++) {
              for (CriticalSectionGroup o : lockOrder.getSuccsOf(afterTn2.get(i))) {
                if (!afterTn2.contains(o)) {
                  afterTn2.add(o);
                }
              }
            }

            if (afterTn2.contains(tn1.group)) {
              if (!optionRepairDeadlock) {
                logger.debug("[DeadlockDetector]  DEADLOCK HAS BEEN DETECTED: not correcting");
                foundDeadlock = true;
              } else {
                logger.debug("[DeadlockDetector]  DEADLOCK HAS BEEN DETECTED: merging group" + (tn1.setNumber) + " and group"
                    + (tn2.setNumber) + " and restarting deadlock detection");

                if (optionPrintDebug) {
                  logger.debug("tn1.setNumber was " + tn1.setNumber + " and tn2.setNumber was " + tn2.setNumber);
                  logger.debug("tn1.group.size was " + tn1.group.criticalSections.size() + " and tn2.group.size was "
                      + tn2.group.criticalSections.size());
                  logger.debug("tn1.group.num was  " + tn1.group.num() + " and tn2.group.num was  " + tn2.group.num());
                }
                tn1.group.mergeGroups(tn2.group);
                if (optionPrintDebug) {
                  logger.debug("tn1.setNumber is  " + tn1.setNumber + " and tn2.setNumber is  " + tn2.setNumber);
                  logger.debug("tn1.group.size is  " + tn1.group.criticalSections.size() + " and tn2.group.size is  "
                      + tn2.group.criticalSections.size());
                }

                foundDeadlock = true;
              }
            }

            lockOrder.addEdge(tn1.group, tn2.group);
          }
        }
      }
    } while (foundDeadlock && optionRepairDeadlock);
    return lockOrder;
  }

  public MutableEdgeLabelledDirectedGraph<Integer, CriticalSection> detectLocksetDeadlock(Map<Value, Integer> lockToLockNum,
      List<PointsToSetInternal> lockPTSets) {
    HashMutableEdgeLabelledDirectedGraph<Integer, CriticalSection> permanentOrder
        = new HashMutableEdgeLabelledDirectedGraph<Integer, CriticalSection>();
    MutableEdgeLabelledDirectedGraph<Integer, CriticalSection> lockOrder;
    boolean foundDeadlock;
    int iteration = 0;
    do {
      iteration++;
      logger.debug("[DeadlockDetector] Deadlock Iteration #" + iteration);
      foundDeadlock = false;
      lockOrder = permanentOrder.clone(); // start each iteration with a fresh copy of the permanent orders

      // Assemble the partial ordering of locks
      Iterator<CriticalSection> deadlockIt1 = criticalSections.iterator();
      while (deadlockIt1.hasNext() && !foundDeadlock) {
        CriticalSection tn1 = deadlockIt1.next();

        // skip if unlocked
        if (tn1.group == null) {
          continue;
        }

        // add a node for each lock in this lockset
        for (EquivalentValue lockEqVal : tn1.lockset) {
          Value lock = lockEqVal.getValue();

          if (!lockOrder.containsNode(lockToLockNum.get(lock))) {
            lockOrder.addNode(lockToLockNum.get(lock));
          }
        }

        // Get list of tn1's target methods
        if (tn1.transitiveTargets == null) {
          tn1.transitiveTargets = new HashSet<MethodOrMethodContext>();
          for (Unit tn1Invoke : tn1.invokes) {
            Iterator<MethodOrMethodContext> targetIt = tt.iterator(tn1Invoke);
            while (targetIt.hasNext()) {
              tn1.transitiveTargets.add(targetIt.next());
            }
          }
        }

        // compare to each other tn
        Iterator<CriticalSection> deadlockIt2 = criticalSections.iterator();
        while (deadlockIt2.hasNext() && !foundDeadlock) {
          CriticalSection tn2 = deadlockIt2.next();

          // skip if unlocked
          if (tn2.group == null) {
            continue;
          }

          // add a node for each lock in this lockset
          for (EquivalentValue lockEqVal : tn2.lockset) {
            Value lock = lockEqVal.getValue();

            if (!lockOrder.containsNode(lockToLockNum.get(lock))) {
              lockOrder.addNode(lockToLockNum.get(lock));
            }
          }

          if (tn1.transitiveTargets.contains(tn2.method) && !foundDeadlock) {
            // This implies the partial ordering (locks in tn1) before (locks in tn2)
            if (true) // optionPrintDebug)
            {
              logger.debug("[DeadlockDetector] locks in " + (tn1.name) + " before locks in " + (tn2.name) + ": " + "outer: "
                  + tn1.name + " inner: " + tn2.name);
            }

            // Check if tn2locks before tn1locks is in our lock order
            for (EquivalentValue lock2EqVal : tn2.lockset) {
              Value lock2 = lock2EqVal.getValue();
              Integer lock2Num = lockToLockNum.get(lock2);

              List<Integer> afterTn2 = new ArrayList<Integer>();
              afterTn2.addAll(lockOrder.getSuccsOf(lock2Num)); // filter here!
              ListIterator<Integer> lit = afterTn2.listIterator();
              while (lit.hasNext()) {
                Integer to = lit.next(); // node the edges go to
                List<CriticalSection> labels = lockOrder.getLabelsForEdges(lock2Num, to);
                boolean keep = false;
                if (labels != null) // this shouldn't really happen... is something wrong with the edge-labelled graph?
                {
                  for (CriticalSection labelTn : labels) {
                    // Check if labelTn and tn1 share a static lock
                    boolean tnsShareAStaticLock = false;
                    for (EquivalentValue tn1LockEqVal : tn1.lockset) {
                      Integer tn1LockNum = lockToLockNum.get(tn1LockEqVal.getValue());
                      if (tn1LockNum < 0) {
                        // this is a static lock... see if some lock in labelTn has the same #
                        for (EquivalentValue labelTnLockEqVal : labelTn.lockset) {
                          if (Objects.equals(lockToLockNum.get(labelTnLockEqVal.getValue()), tn1LockNum)) {
                            tnsShareAStaticLock = true;
                          }
                        }
                      }
                    }

                    if (!tnsShareAStaticLock) // !hasStaticLockInCommon(tn1, labelTn))
                    {
                      keep = true;
                      break;
                    }
                  }
                }
                if (!keep) {
                  lit.remove();
                }
              }
              /*
               * for (int i = 0; i < afterTn2.size(); i++) { List<Integer> succs = lockOrder.getSuccsOf(afterTn2.get(i)); //
               * but not here for (Integer o : succs) { if (!afterTn2.contains(o)) { afterTn2.add(o); } } }
               */
              for (EquivalentValue lock1EqVal : tn1.lockset) {
                Value lock1 = lock1EqVal.getValue();
                Integer lock1Num = lockToLockNum.get(lock1);

                if ((!Objects.equals(lock1Num, lock2Num) || lock1Num > 0) && afterTn2.contains(lock1Num)) {
                  if (!optionRepairDeadlock) {
                    logger.debug("[DeadlockDetector] DEADLOCK HAS BEEN DETECTED: not correcting");
                    foundDeadlock = true;
                  } else {
                    logger.debug("[DeadlockDetector] DEADLOCK HAS BEEN DETECTED while inspecting " + lock1Num + " (" + lock1
                        + ") and " + lock2Num + " (" + lock2 + ") ");

                    // Create a deadlock avoidance edge
                    DeadlockAvoidanceEdge dae = new DeadlockAvoidanceEdge(tn1.method.getDeclaringClass());
                    EquivalentValue daeEqVal = new EquivalentValue(dae);

                    // Register it as a static lock
                    Integer daeNum = -lockPTSets.size(); // negative indicates a static lock
                    permanentOrder.addNode(daeNum);
                    lockToLockNum.put(dae, daeNum);
                    PointsToSetInternal dummyLockPT
                        = new HashPointsToSet(lock1.getType(), (PAG) Scene.v().getPointsToAnalysis());
                    lockPTSets.add(dummyLockPT);

                    // Add it to the locksets of tn1 and whoever says l2 before l1
                    for (EquivalentValue lockEqVal : tn1.lockset) {
                      Integer lockNum = lockToLockNum.get(lockEqVal.getValue());
                      if (!permanentOrder.containsNode(lockNum)) {
                        permanentOrder.addNode(lockNum);
                      }
                      permanentOrder.addEdge(daeNum, lockNum, tn1);
                    }
                    tn1.lockset.add(daeEqVal);

                    List<CriticalSection> forwardLabels = lockOrder.getLabelsForEdges(lock1Num, lock2Num);
                    if (forwardLabels != null) {
                      for (CriticalSection tn : forwardLabels) {
                        if (!tn.lockset.contains(daeEqVal)) {
                          for (EquivalentValue lockEqVal : tn.lockset) {
                            Integer lockNum = lockToLockNum.get(lockEqVal.getValue());
                            if (!permanentOrder.containsNode(lockNum)) {
                              permanentOrder.addNode(lockNum);
                            }
                            permanentOrder.addEdge(daeNum, lockNum, tn);
                          }
                          tn.lockset.add(daeEqVal);
                        }
                      }
                    }

                    List<CriticalSection> backwardLabels = lockOrder.getLabelsForEdges(lock2Num, lock1Num);
                    if (backwardLabels != null) {
                      for (CriticalSection tn : backwardLabels) {
                        if (!tn.lockset.contains(daeEqVal)) {
                          for (EquivalentValue lockEqVal : tn.lockset) {
                            Integer lockNum = lockToLockNum.get(lockEqVal.getValue());
                            if (!permanentOrder.containsNode(lockNum)) {
                              permanentOrder.addNode(lockNum);
                            }
                            permanentOrder.addEdge(daeNum, lockNum, tn);
                          }
                          tn.lockset.add(daeEqVal);
                          logger.debug("[DeadlockDetector]   Adding deadlock avoidance edge between " + (tn1.name) + " and "
                              + (tn.name));
                        }
                      }
                      logger.debug("[DeadlockDetector]   Restarting deadlock detection");
                    }

                    foundDeadlock = true;
                    break;
                  }
                }

                if (!Objects.equals(lock1Num, lock2Num)) {
                  lockOrder.addEdge(lock1Num, lock2Num, tn1);
                }
              }
              if (foundDeadlock) {
                break;
              }
            }
          }
        }
      }
    } while (foundDeadlock && optionRepairDeadlock);
    return lockOrder;
  }

  public void reorderLocksets(Map<Value, Integer> lockToLockNum,
      MutableEdgeLabelledDirectedGraph<Integer, CriticalSection> lockOrder) {
    for (CriticalSection tn : criticalSections) {
      // Get the portion of the lock order that is visible to tn
      HashMutableDirectedGraph<Integer> visibleOrder = new HashMutableDirectedGraph<Integer>();
      if (tn.group != null) {
        for (CriticalSection otherTn : criticalSections) {
          // Check if otherTn and tn share a static lock
          boolean tnsShareAStaticLock = false;
          for (EquivalentValue tnLockEqVal : tn.lockset) {
            Integer tnLockNum = lockToLockNum.get(tnLockEqVal.getValue());
            if (tnLockNum < 0) {
              // this is a static lock... see if some lock in labelTn has the same #
              if (otherTn.group != null) {
                for (EquivalentValue otherTnLockEqVal : otherTn.lockset) {
                  if (Objects.equals(lockToLockNum.get(otherTnLockEqVal.getValue()), tnLockNum)) {
                    tnsShareAStaticLock = true;
                  }
                }
              } else {
                tnsShareAStaticLock = true; // not really... but we want to skip this one
              }
            }
          }

          if (!tnsShareAStaticLock || tn == otherTn) // if tns don't share any static lock, or if tns are the same one
          {
            // add these orderings to tn's visible order
            DirectedGraph<Integer> orderings = lockOrder.getEdgesForLabel(otherTn);
            for (Integer node1 : orderings) {
              if (!visibleOrder.containsNode(node1)) {
                visibleOrder.addNode(node1);
              }
              for (Integer node2 : orderings.getSuccsOf(node1)) {
                if (!visibleOrder.containsNode(node2)) {
                  visibleOrder.addNode(node2);
                }
                visibleOrder.addEdge(node1, node2);
              }
            }
          }
        }

        logger.debug("VISIBLE ORDER FOR " + tn.name);
        visibleOrder.printGraph();

        // Order locks in tn's lockset according to the visible order (insertion sort)
        List<EquivalentValue> newLockset = new ArrayList<EquivalentValue>();
        for (EquivalentValue lockEqVal : tn.lockset) {
          Value lockToInsert = lockEqVal.getValue();
          Integer lockNumToInsert = lockToLockNum.get(lockToInsert);
          int i = 0;
          while (i < newLockset.size()) {
            EquivalentValue existingLockEqVal = newLockset.get(i);
            Value existingLock = existingLockEqVal.getValue();
            Integer existingLockNum = lockToLockNum.get(existingLock);
            if (visibleOrder.containsEdge(lockNumToInsert, existingLockNum) || lockNumToInsert < existingLockNum)
            // !visibleOrder.containsEdge(existingLockNum,
            // lockNumToInsert)
            // ) //
            // if(!
            // existing
            // before
            // toinsert
            // )
            {
              break;
            }
            i++;
          }
          newLockset.add(i, lockEqVal);
        }
        logger.debug("reordered from " + LockAllocator.locksetToLockNumString(tn.lockset, lockToLockNum) + " to "
            + LockAllocator.locksetToLockNumString(newLockset, lockToLockNum));

        tn.lockset = newLockset;
      }
    }
  }
}
