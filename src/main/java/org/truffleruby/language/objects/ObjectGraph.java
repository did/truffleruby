/*
 * Copyright (c) 2013, 2018 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.objects;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Property;
import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.cext.ValueWrapper;
import org.truffleruby.core.hash.Entry;
import org.truffleruby.core.queue.SizedQueue;
import org.truffleruby.core.queue.UnsizedQueue;
import org.truffleruby.language.arguments.RubyArguments;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

public abstract class ObjectGraph {

    public static Set<DynamicObject> newRubyObjectSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    @TruffleBoundary
    public static Set<DynamicObject> stopAndGetAllObjects(Node currentNode, final RubyContext context) {
        context.getMarkingService().queueMarking();
        final Set<DynamicObject> visited = newRubyObjectSet();

        final Thread initiatingJavaThread = Thread.currentThread();

        context.getSafepointManager().pauseAllThreadsAndExecute(currentNode, false, (thread, currentNode1) -> {
            synchronized (visited) {
                final Deque<DynamicObject> stack = new ArrayDeque<>();

                // Thread.current
                stack.add(thread);
                // Fiber.current
                stack.add(Layouts.THREAD.getFiberManager(thread).getCurrentFiber());

                if (Thread.currentThread() == initiatingJavaThread) {
                    visitContextRoots(context, stack);
                }

                Truffle.getRuntime().iterateFrames(frameInstance -> {
                    stack.addAll(getObjectsInFrame(frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY)));
                    return null;
                });

                while (!stack.isEmpty()) {
                    final DynamicObject object = stack.pop();

                    if (visited.add(object)) {
                        stack.addAll(ObjectGraph.getAdjacentObjects(object));
                    }
                }
            }
        });

        return visited;
    }

    @TruffleBoundary
    public static Set<DynamicObject> stopAndGetRootObjects(Node currentNode, final RubyContext context) {
        final Set<DynamicObject> visited = newRubyObjectSet();

        final Thread initiatingJavaThread = Thread.currentThread();

        context.getSafepointManager().pauseAllThreadsAndExecute(currentNode, false, (thread, currentNode1) -> {
            synchronized (visited) {
                visited.add(thread);

                if (Thread.currentThread() == initiatingJavaThread) {
                    visitContextRoots(context, visited);
                }
            }
        });

        return visited;
    }

    public static void visitContextRoots(RubyContext context, Collection<DynamicObject> roots) {
        // We do not want to expose the global object
        roots.addAll(context.getCoreLibrary().getGlobalVariables().dynamicObjectValues());
        roots.addAll(context.getAtExitManager().getHandlers());
        context.getFinalizationService().collectRoots(roots);
    }

    public static Set<DynamicObject> getAdjacentObjects(DynamicObject object) {
        final Set<DynamicObject> reachable = newRubyObjectSet();

        if (Layouts.BASIC_OBJECT.isBasicObject(object)) {
            reachable.add(Layouts.BASIC_OBJECT.getLogicalClass(object));
            reachable.add(Layouts.BASIC_OBJECT.getMetaClass(object));
        }

        for (Property property : object.getShape().getPropertyListInternal(false)) {
            final Object propertyValue = property.get(object, object.getShape());

            if (propertyValue instanceof DynamicObject) {
                reachable.add((DynamicObject) propertyValue);
            } else if (propertyValue instanceof Entry[]) {
                for (Entry bucket : (Entry[]) propertyValue) {
                    while (bucket != null) {
                        if (bucket.getKey() instanceof DynamicObject) {
                            reachable.add((DynamicObject) bucket.getKey());
                        }

                        if (bucket.getValue() instanceof DynamicObject) {
                            reachable.add((DynamicObject) bucket.getValue());
                        }

                        bucket = bucket.getNextInLookup();
                    }
                }
            } else if (propertyValue instanceof Object[]) {
                for (Object element : (Object[]) propertyValue) {
                    // Needed to get wrappers set by Truffle::CExt.set_mark_list_on_object.
                    if (element instanceof ValueWrapper) {
                        element = ((ValueWrapper) element).getObject();
                    }
                    if (element instanceof DynamicObject) {
                        reachable.add((DynamicObject) element);
                    }
                }
            } else if (propertyValue instanceof Collection<?>) {
                for (Object element : ((Collection<?>) propertyValue)) {
                    if (element instanceof DynamicObject) {
                        reachable.add((DynamicObject) element);
                    }
                }
            } else if (propertyValue instanceof SizedQueue) {
                for (Object element : ((SizedQueue) propertyValue).getContents()) {
                    if (element instanceof DynamicObject) {
                        reachable.add((DynamicObject) element);
                    }
                }
            } else if (propertyValue instanceof UnsizedQueue) {
                for (Object element : ((UnsizedQueue) propertyValue).getContents()) {
                    if (element instanceof DynamicObject) {
                        reachable.add((DynamicObject) element);
                    }
                }
            } else if (propertyValue instanceof Frame) {
                reachable.addAll(getObjectsInFrame((Frame) propertyValue));
            } else if (propertyValue instanceof ObjectGraphNode) {
                ((ObjectGraphNode) propertyValue).getAdjacentObjects(reachable);
            }
        }

        return reachable;
    }

    public static Set<DynamicObject> getObjectsInFrame(Frame frame) {
        final Set<DynamicObject> objects = newRubyObjectSet();

        final Frame lexicalParentFrame = RubyArguments.tryGetDeclarationFrame(frame);
        if (lexicalParentFrame != null) {
            objects.addAll(getObjectsInFrame(lexicalParentFrame));
        }

        final Object self = RubyArguments.tryGetSelf(frame);
        if (self instanceof DynamicObject) {
            objects.add((DynamicObject) self);
        }

        final DynamicObject block = RubyArguments.tryGetBlock(frame);
        if (block != null) {
            objects.add(block);
        }

        // Other frame arguments are either only internal or user arguments which appear in slots.

        for (FrameSlot slot : frame.getFrameDescriptor().getSlots()) {
            final Object slotValue = frame.getValue(slot);

            if (slotValue instanceof DynamicObject) {
                objects.add((DynamicObject) slotValue);
            }
        }

        return objects;
    }

}
