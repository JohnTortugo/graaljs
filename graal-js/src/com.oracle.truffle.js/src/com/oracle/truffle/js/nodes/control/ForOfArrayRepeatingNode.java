package com.oracle.truffle.js.nodes.control;

import java.util.Set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.JSWriteFrameSlotNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;

/**
 * For-of loops specific for arrays.
 */
public final class ForOfArrayRepeatingNode extends AbstractRepeatingNode {

    @Child private JSWriteFrameSlotNode writeToBinding;
    @Child private JavaScriptNode bodyNode;

    @CompilationFinal private JSDynamicObject array;
    @CompilationFinal private int length;

    private int index;

    public ForOfArrayRepeatingNode(JSDynamicObject array, JSWriteFrameSlotNode write, JavaScriptNode bodyNode) {
        super(null, bodyNode);
        this.array = array;
        this.writeToBinding = write;
        this.bodyNode = bodyNode;
        this.length = (int) JSArray.arrayGetLength(array);
        this.index = 0;
    }

    @Override
    public boolean executeRepeating(VirtualFrame frame) {
        if (index >= length) {
            return false; // loop end
        }

        Object elementValue = JSObject.get(array, index++);
        writeToBinding.executeWrite(frame, elementValue);

        try {
            executeBody(frame);
        } finally {
            // Clear the binding after every execution of the loop body.
            // This is required by the JS spec.
            writeToBinding.executeWrite(frame, Undefined.instance);
        }

        return true; // continue loop
    }

    @Override
    public Object resume(VirtualFrame frame, int stateSlot) {
        throw Errors.shouldNotReachHere();
    }

    @Override
    protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
        return new ForOfArrayRepeatingNode(array,
                        cloneUninitialized(writeToBinding, materializedTags),
                        cloneUninitialized(bodyNode, materializedTags));
    }

    @Override
    public AbstractRepeatingNode materializeInstrumentableNodes(Set<Class<? extends Tag>> materializedTags) {
        if (!materializationNeeded()) {
            return this;
        }
        return new ForOfArrayRepeatingNode(array,
                        cloneUninitialized(writeToBinding, materializedTags),
                        materializeBody(materializedTags));
    }
}