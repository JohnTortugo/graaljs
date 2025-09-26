/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.control;

import java.util.Set;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.IteratorCompleteNode;
import com.oracle.truffle.js.nodes.access.IteratorValueNode;
import com.oracle.truffle.js.nodes.access.JSWriteFrameSlotNode;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.Strings;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSArrayIteratorObject;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.objects.IteratorRecord;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;

/**
 * For-in/of/await-of loop.
 */
public abstract class ForOfRepeatingNode extends AbstractRepeatingNode implements ResumableNode.WithIntState {

    @CompilationFinal private boolean initialized;
    @CompilationFinal private boolean isForOf;
    @CompilationFinal private boolean isForOfAsync;

    @Child JavaScriptNode iteratorNode;
    @Child JavaScriptNode nextResultNode;
    @Child JSWriteFrameSlotNode writeNextValueNode;
    @Child IteratorCompleteNode iteratorCompleteNode = IteratorCompleteNode.create();
    @Child IteratorValueNode iteratorValueNode = IteratorValueNode.create();

    public static ForOfRepeatingNode create(boolean isForOf, boolean isForOfAsync, JavaScriptNode iteratorNode, JavaScriptNode nextResultNode, JavaScriptNode body,
                    JSWriteFrameSlotNode writeNextValueNode) {
        return ForOfRepeatingNodeGen.create(isForOf, isForOfAsync, iteratorNode, nextResultNode, body, writeNextValueNode);
    }

    protected ForOfRepeatingNode(boolean isForOf, boolean isForOfAsync, JavaScriptNode iteratorNode, JavaScriptNode nextResultNode, JavaScriptNode body, JSWriteFrameSlotNode writeNextValueNode) {
        super(null, body);
        this.isForOf = isForOf;
        this.isForOfAsync = isForOfAsync;
        this.initialized = false;
        this.iteratorNode = iteratorNode;
        this.nextResultNode = nextResultNode;
        this.writeNextValueNode = writeNextValueNode;
    }

    public abstract boolean executeBoolean(VirtualFrame frame);

    private Object executeNextResult(VirtualFrame frame) {
        return nextResultNode.execute(frame);
    }

    @Override
    public boolean executeRepeating(VirtualFrame frame) {
        return executeBoolean(frame);
    }

    @Override
    public final Object executeRepeatingWithValue(VirtualFrame frame) {
        return super.executeRepeatingWithValue(frame);
    }

    @Specialization
    protected boolean doGeneric(VirtualFrame frame) {
        if (!initialized && isForOf && !isForOfAsync) {
            this.initialized = true;
            CompilerDirectives.transferToInterpreterAndInvalidate();

            // Evaluate once (not per iteration):
            IteratorRecord rec = (IteratorRecord) iteratorNode.execute(frame);

            // Try to switch to fast array path
            JSDynamicObject fastArray = tryExtractFastArray(rec);
            if (fastArray != null) {
                ForOfArrayRepeatingNode fast = new ForOfArrayRepeatingNode(fastArray, writeNextValueNode, bodyNode);
                replace(fast);
                return fast.executeRepeating(frame); // tail-call into fast path
            }
        }

        Object nextResult = executeNextResult(frame);
        Object what = iteratorNode.execute(frame);
        boolean done = iteratorCompleteNode.execute(nextResult);
        Object nextValue = iteratorValueNode.execute(nextResult);
        if (done) {
            return false;
        }
        writeNextValueNode.executeWrite(frame, nextValue);
        try {
            executeBody(frame);
        } finally {
            writeNextValueNode.executeWrite(frame, Undefined.instance);
        }
        return true;
    }

    @Override
    public Object resume(VirtualFrame frame, int stateSlot) {
        int state = getStateAsIntAndReset(frame, stateSlot);
        if (state == 0) {
            Object nextResult = executeNextResult(frame);
            boolean done = iteratorCompleteNode.execute(nextResult);
            Object nextValue = iteratorValueNode.execute(nextResult);
            if (done) {
                return false;
            }
            writeNextValueNode.executeWrite(frame, nextValue);
        }
        try {
            executeBody(frame);
        } catch (YieldException e) {
            setStateAsInt(frame, stateSlot, 1);
            throw e;
        } finally {
            writeNextValueNode.executeWrite(frame, Undefined.instance);
        }
        return true;
    }

    @Override
    protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
        return ForOfRepeatingNode.create(isForOf, isForOfAsync,
                        cloneUninitialized(iteratorNode, materializedTags),
                        cloneUninitialized(nextResultNode, materializedTags),
                        cloneUninitialized(bodyNode, materializedTags),
                        cloneUninitialized(writeNextValueNode, materializedTags));
    }

    @Override
    public AbstractRepeatingNode materializeInstrumentableNodes(Set<Class<? extends Tag>> materializedTags) {
        if (!materializationNeeded()) {
            return this;
        }
        return ForOfRepeatingNode.create(isForOf, isForOfAsync,
                        cloneUninitialized(iteratorNode, materializedTags),
                        cloneUninitialized(nextResultNode, materializedTags),
                        materializeBody(materializedTags),
                        cloneUninitialized(writeNextValueNode, materializedTags));
    }

    private JSDynamicObject tryExtractFastArray(IteratorRecord rec) {
        Object it = rec.getIterator();
        if (!(it instanceof JSArrayIteratorObject)) {
            return null;
        }

        JSArrayIteratorObject arrIter = (JSArrayIteratorObject) it;
        if (arrIter.getIterationKind() != JSRuntime.ITERATION_KIND_VALUE) {
            return null;
        }

        JSDynamicObject proto = JSObject.getPrototype((JSDynamicObject) rec.getIterator());
        // Must be the initial %ArrayIteratorPrototype%
        if (proto != getRealm().getArrayIteratorPrototype()) {
            return null;
        }

        // Resolve current 'next' on the prototype and require identity match
        Object currentNext = JSObject.getOrDefault(proto, Strings.NEXT, proto, Undefined.instance);
        if (rec.getNextMethod() != currentNext) {
            return null;
        }

        // Underlying array
        JSDynamicObject array = (JSDynamicObject) arrIter.getIteratedObject();
        if (array == null) {
            return null;
        }

        // Array must be safe for direct indexing
        if (JSProxy.isJSProxy(array)) {
            return null;
        }

        // Check if it's a fast array. Do I need to check if it has holes?!
        if (!JSArray.isJSFastArray(array)) {
            return null;
        }

        // Proto chain must be the initial, unmodified built-ins:
        if (JSObject.getPrototype(array) != getRealm().getArrayPrototype()) {
            return null;
        }

        if (JSObject.getPrototype(getRealm().getArrayPrototype()) != getRealm().getObjectPrototype()) {
            return null;
        }

        return array;
    }
}