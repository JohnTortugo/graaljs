package com.oracle.truffle.js.nodes.control;

import java.util.Set;
import com.oracle.truffle.js.runtime.Strings;
import com.oracle.truffle.js.runtime.objects.IteratorRecord;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.IteratorCompleteNode;
import com.oracle.truffle.js.nodes.access.IteratorValueNode;
import com.oracle.truffle.js.nodes.access.JSWriteFrameSlotNode;
import com.oracle.truffle.js.nodes.array.ArrayLengthNode;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.array.ScriptArray;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.builtins.JSArrayIteratorObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.nodes.access.ReadElementNode;

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