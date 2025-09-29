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

import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.nodes.access.ReadElementNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.JSWriteFrameSlotNode;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;

/**
 * For-of loops specific for arrays.
 */
public final class ForOfArrayRepeatingNode extends AbstractRepeatingNode {

    @Child private JSWriteFrameSlotNode writeNextValueNode;
    @Child private ReadElementNode readEl;

    private final BranchProfile notActiveProfile = BranchProfile.create();
    private final BranchProfile earlyTerminationProfile = BranchProfile.create();
    private final BranchProfile normalTerminationProfile = BranchProfile.create();

    @CompilationFinal private JSDynamicObject array;

    private JSContext context;

    // The length of JavaScript arrays is limited to 2^32-1
    private int length;
    private int index;
    private boolean active;

    public ForOfArrayRepeatingNode(JSContext context, JSDynamicObject array, JSWriteFrameSlotNode writeNextValueNode, JavaScriptNode bodyNode) {
        super(null, bodyNode);
        this.context = context;
        this.array = array;
        this.writeNextValueNode = writeNextValueNode;
        this.readEl = ReadElementNode.create(context);
        this.active = false;
    }

    @Override
    public boolean executeRepeating(VirtualFrame frame) {
        if (!this.active) {
            notActiveProfile.enter();
            this.active = true;
            this.index = 0;
            this.length = 1000000; // (int) JSArray.arrayGetLength(array);
        }

        if (index >= length) {
            normalTerminationProfile.enter();
            this.active = false;
            return false; // loop end
        }

        // Reads the element and store it in the binding variable
        // Object elementValue = JSObject.get(array, index++);
        Object elementValue = readEl.executeWithTargetAndIndex(array, index++);
        writeNextValueNode.executeWrite(frame, elementValue);

        try {
            executeBody(frame);
        } catch (BreakException | ReturnException e) {
            // Loop terminated early: make the node ready for the *next* run
            earlyTerminationProfile.enter();
            this.active = false;
            throw e;
        } catch (RuntimeException e) {
            // Any other abrupt completion ends the loop as well
            this.active = false;
            throw e;
        } finally {
            writeNextValueNode.executeWrite(frame, Undefined.instance);
        }

        return true; // continue loop
    }

    @Override
    protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
        return new ForOfArrayRepeatingNode(context, array,
                        cloneUninitialized(writeNextValueNode, materializedTags),
                        cloneUninitialized(bodyNode, materializedTags));
    }

    @Override
    public AbstractRepeatingNode materializeInstrumentableNodes(Set<Class<? extends Tag>> materializedTags) {
        if (!materializationNeeded()) {
            return this;
        }
        return new ForOfArrayRepeatingNode(context, array,
                        cloneUninitialized(writeNextValueNode, materializedTags),
                        materializeBody(materializedTags));
    }
}