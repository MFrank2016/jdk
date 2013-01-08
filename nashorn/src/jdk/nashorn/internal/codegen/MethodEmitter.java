/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.nashorn.internal.codegen;

import static jdk.internal.org.objectweb.asm.Opcodes.ATHROW;
import static jdk.internal.org.objectweb.asm.Opcodes.CHECKCAST;
import static jdk.internal.org.objectweb.asm.Opcodes.DUP2;
import static jdk.internal.org.objectweb.asm.Opcodes.GETFIELD;
import static jdk.internal.org.objectweb.asm.Opcodes.GETSTATIC;
import static jdk.internal.org.objectweb.asm.Opcodes.GOTO;
import static jdk.internal.org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static jdk.internal.org.objectweb.asm.Opcodes.IFEQ;
import static jdk.internal.org.objectweb.asm.Opcodes.IFGE;
import static jdk.internal.org.objectweb.asm.Opcodes.IFGT;
import static jdk.internal.org.objectweb.asm.Opcodes.IFLE;
import static jdk.internal.org.objectweb.asm.Opcodes.IFLT;
import static jdk.internal.org.objectweb.asm.Opcodes.IFNE;
import static jdk.internal.org.objectweb.asm.Opcodes.IFNONNULL;
import static jdk.internal.org.objectweb.asm.Opcodes.IFNULL;
import static jdk.internal.org.objectweb.asm.Opcodes.IF_ACMPEQ;
import static jdk.internal.org.objectweb.asm.Opcodes.IF_ACMPNE;
import static jdk.internal.org.objectweb.asm.Opcodes.IF_ICMPEQ;
import static jdk.internal.org.objectweb.asm.Opcodes.IF_ICMPGE;
import static jdk.internal.org.objectweb.asm.Opcodes.IF_ICMPGT;
import static jdk.internal.org.objectweb.asm.Opcodes.IF_ICMPLE;
import static jdk.internal.org.objectweb.asm.Opcodes.IF_ICMPLT;
import static jdk.internal.org.objectweb.asm.Opcodes.IF_ICMPNE;
import static jdk.internal.org.objectweb.asm.Opcodes.INSTANCEOF;
import static jdk.internal.org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static jdk.internal.org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static jdk.internal.org.objectweb.asm.Opcodes.INVOKESTATIC;
import static jdk.internal.org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static jdk.internal.org.objectweb.asm.Opcodes.NEW;
import static jdk.internal.org.objectweb.asm.Opcodes.PUTFIELD;
import static jdk.internal.org.objectweb.asm.Opcodes.PUTSTATIC;
import static jdk.internal.org.objectweb.asm.Opcodes.RETURN;
import static jdk.nashorn.internal.codegen.CompilerConstants.CONSTANTS;
import static jdk.nashorn.internal.codegen.CompilerConstants.THIS;
import static jdk.nashorn.internal.codegen.CompilerConstants.THIS_DEBUGGER;
import static jdk.nashorn.internal.codegen.CompilerConstants.className;
import static jdk.nashorn.internal.codegen.CompilerConstants.constructorNoLookup;
import static jdk.nashorn.internal.codegen.CompilerConstants.methodDescriptor;
import static jdk.nashorn.internal.codegen.CompilerConstants.staticField;
import static jdk.nashorn.internal.codegen.CompilerConstants.virtualCallNoLookup;

import java.io.PrintStream;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Iterator;
import jdk.internal.org.objectweb.asm.Handle;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.nashorn.internal.codegen.ClassEmitter.Flag;
import jdk.nashorn.internal.codegen.CompilerConstants.Call;
import jdk.nashorn.internal.codegen.CompilerConstants.FieldAccess;
import jdk.nashorn.internal.codegen.types.ArrayType;
import jdk.nashorn.internal.codegen.types.BitwiseType;
import jdk.nashorn.internal.codegen.types.NumericType;
import jdk.nashorn.internal.codegen.types.Type;
import jdk.nashorn.internal.ir.FunctionNode;
import jdk.nashorn.internal.ir.IdentNode;
import jdk.nashorn.internal.ir.LiteralNode;
import jdk.nashorn.internal.ir.RuntimeNode;
import jdk.nashorn.internal.ir.SplitNode;
import jdk.nashorn.internal.ir.Symbol;
import jdk.nashorn.internal.runtime.Context;
import jdk.nashorn.internal.runtime.DebugLogger;
import jdk.nashorn.internal.runtime.JSType;
import jdk.nashorn.internal.runtime.Scope;
import jdk.nashorn.internal.runtime.linker.Bootstrap;
import jdk.nashorn.internal.runtime.options.Options;
import org.dynalang.dynalink.support.NameCodec;

/**
 * This is the main function responsible for emitting method code
 * in a class. It maintains a type stack and keeps track of control
 * flow to make sure that the registered instructions don't violate
 * byte code verification.
 *
 * Running Nashorn with -ea will assert as soon as a type stack
 * becomes corrupt, for easier debugging
 *
 * Running Nashorn with -Dnashorn.codegen.debug=true will print
 * all generated bytecode and labels to stderr, for easier debugging,
 * including bytecode stack contents
 */
public class MethodEmitter implements Emitter {
    /** The ASM MethodVisitor we are plugged into */
    private final MethodVisitor method;

    /** Current type stack for current evaluation */
    protected ArrayDeque<Type> stack;

    /** Parent classEmitter representing the class of this method */
    private final ClassEmitter classEmitter;

    /** FunctionNode representing this method, or null if none exists */
    private FunctionNode functionNode;

    /** SplitNode representing the current split, or null if none exists */
    private SplitNode splitNode;

    /** The context */
    private final Context context;

    /** Threshold in chars for when string constants should be split */
    static final int LARGE_STRING_THRESHOLD = 32 * 1024;

    /** Debug flag, should we dump all generated bytecode along with stacks? */
    private static final DebugLogger LOG   = new DebugLogger("codegen", "nashorn.codegen.debug");
    private static final boolean     DEBUG = LOG.isEnabled();

    /** dump stack on a particular line, or -1 if disabled */
    private static final int DEBUG_TRACE_LINE;

    static {
        final String tl = Options.getStringProperty("nashorn.codegen.debug.trace", "-1");
        int line = -1;
        try {
            line = Integer.parseInt(tl);
        } catch (final NumberFormatException e) {
            //fallthru
        }
        DEBUG_TRACE_LINE = line;
    }

    /** Bootstrap for normal indy:s */
    private static final Handle LINKERBOOTSTRAP  = new Handle(H_INVOKESTATIC, Bootstrap.BOOTSTRAP.className(), Bootstrap.BOOTSTRAP.name(), Bootstrap.BOOTSTRAP.descriptor());

    /** Bootstrap for runtime node indy:s */
    private static final Handle RUNTIMEBOOTSTRAP = new Handle(H_INVOKESTATIC, RuntimeCallSite.BOOTSTRAP.className(), RuntimeCallSite.BOOTSTRAP.name(), RuntimeCallSite.BOOTSTRAP.descriptor());

    /**
     * Constructor - internal use from ClassEmitter only
     * @see ClassEmitter#method
     *
     * @param classEmitter the class emitter weaving the class this method is in
     * @param method       a method visitor
     */
    MethodEmitter(final ClassEmitter classEmitter, final MethodVisitor method) {
        this(classEmitter, method, null);
    }

    /**
     * Constructor - internal use from ClassEmitter only
     * @see ClassEmitter#method
     *
     * @param classEmitter the class emitter weaving the class this method is in
     * @param method       a method visitor
     * @param functionNode a function node representing this method
     */
    MethodEmitter(final ClassEmitter classEmitter, final MethodVisitor method, final FunctionNode functionNode) {
        this.context      = classEmitter.getContext();
        this.classEmitter = classEmitter;
        this.method       = method;
        this.functionNode = functionNode;
        this.stack        = null;
    }

    /**
     * Begin a method
     * @see Emitter
     */
    @Override
    public void begin() {
        classEmitter.beginMethod(this);
        stack = new ArrayDeque<>();
        method.visitCode();
    }

    /**
     * End a method
     * @see Emitter
     */
    @Override
    public void end() {
        method.visitMaxs(0, 0);
        method.visitEnd();

        classEmitter.endMethod(this);
    }

    @Override
    public String toString() {
        return "methodEmitter: " + (functionNode == null ? method : functionNode.getName()).toString();
    }

    /**
     * Push a type to the existing stack
     * @param type the type
     */
    private void pushType(final Type type) {
        if (type != null) {
            stack.push(type);
        }
    }

    /**
     * Pop a type from the existing stack
     *
     * @param expected expected type - will assert if wrong
     *
     * @return the type that was retrieved
     */
    private Type popType(final Type expected) {
        final Type type = stack.pop();
        assert type.isObject() && expected.isObject() ||
            type.isEquivalentTo(expected) : type + " is not compatible with " + expected;
        return type;
    }

    /**
     * Pop a type from the existing stack, no matter what it is.
     *
     * @return the type
     */
    private Type popType() {
        return stack.pop();
    }

    /**
     * Pop a type from the existing stack, ensuring that it is numeric,
     * assert if not
     *
     * @return the type
     */
    private NumericType popNumeric() {
        final Type type = stack.pop();
        assert type.isNumeric() : type + " is not numeric";
        return (NumericType)type;
    }

    /**
     * Pop a type from the existing stack, ensuring that it is an integer type
     * (integer or long), assert if not
     *
     * @return the type
     */
    private BitwiseType popInteger() {
        final Type type = stack.pop();
        assert type.isInteger() || type.isLong() : type + " is not an integer or long";
        return (BitwiseType)type;
    }

    /**
     * Pop a type from the existing stack, ensuring that it is an array type,
     * assert if not
     *
     * @return the type
     */
    private ArrayType popArray() {
        final Type type = stack.pop();
        assert type.isArray();
        return (ArrayType)type;
    }

    /**
     * Peek a given number of slots from the top of the stack and return the
     * type in that slot
     *
     * @param pos the number of positions from the top, 0 is the top element
     *
     * @return the type at position "pos" on the stack
     */
    public final Type peekType(final int pos) {
        final Iterator<Type> iter = stack.iterator();
        for (int i = 0; i < pos; i++) {
            iter.next();
        }
        return iter.next();
    }

    /**
     * Peek at the type at the top of the stack
     *
     * @return the type at the top of the stack
     */
    public final Type peekType() {
        return stack.peek();
    }

    /**
     * Generate code a for instantiating a new object and push the
     * object type on the stack
     *
     * @param classDescriptor class descriptor for the object type
     *
     * @return the method emitter
     */
    public MethodEmitter _new(final String classDescriptor) {
        method.visitTypeInsn(NEW, classDescriptor);
        pushType(Type.OBJECT);
        return this;
    }

    /**
     * Generate code a for instantiating a new object and push the
     * object type on the stack
     *
     * @param clazz class type to instatiate
     *
     * @return the method emitter
     */
    public MethodEmitter _new(final Class<?> clazz) {
        return _new(className(clazz));
    }

    /**
     * Generate code to call the empty constructor for a class
     *
     * @param clazz class type to instatiate
     *
     * @return the method emitter
     */
    public MethodEmitter newInstance(final Class<?> clazz) {
        return invoke(constructorNoLookup(clazz));
    }

    /**
     * Perform a dup, that is, duplicate the top element and
     * push the duplicate down a given number of positions
     * on the stack. This is totally type agnostic.
     *
     * @param depth the depth on which to put the copy
     *
     * @return the method emitter, or null if depth is illegal and
     *  has no instruction equivalent.
     */
    public MethodEmitter dup(final int depth) {
        if (peekType().dup(method, depth) == null) {
            return null;
        }

        debug("dup", depth);

        switch (depth) {
        case 0:
            pushType(peekType());
            break;
        case 1: {
            final Type p0 = popType();
            final Type p1 = popType();
            pushType(p0);
            pushType(p1);
            pushType(p0);
            break;
        }
        case 2: {
            final Type p0 = popType();
            final Type p1 = popType();
            final Type p2 = popType();
            pushType(p0);
            pushType(p2);
            pushType(p1);
            pushType(p0);
            break;
        }
        default:
            assert false : "illegal dup depth = " + depth;
            return null;
        }

        return this;
    }

    /**
     * Perform a dup2, that is, duplicate the top element if it
     * is a category 2 type, or two top elements if they are category
     * 1 types, and push them on top of the stack
     *
     * @return the method emitter
     */
    public MethodEmitter dup2() {
        debug("dup2");

        if (peekType().isCategory2()) {
            pushType(peekType());
        } else {
            final Type type = get2();
            pushType(type);
            pushType(type);
            pushType(type);
            pushType(type);
        }
        method.visitInsn(DUP2);
        return this;
    }

    /**
     * Duplicate the top element on the stack and push it
     *
     * @return the method emitter
     */
    public MethodEmitter dup() {
        return dup(0);
    }

    /**
     * Pop the top element of the stack and throw it away
     *
     * @return the method emitter
     */
    public MethodEmitter pop() {
        debug("pop", peekType());
        popType().pop(method);
        return this;
    }

    /**
     * Pop the top element of the stack if category 2 type, or the two
     * top elements of the stack if category 1 types
     *
     * @return the method emitter
     */
    public MethodEmitter pop2() {
        if (peekType().isCategory2()) {
            popType();
        } else {
            get2n();
        }
        return this;
    }

    /**
     * Swap the top two elements of the stack. This is totally
     * type agnostic and works for all types
     *
     * @return the method emitter
     */
    public MethodEmitter swap() {
        debug("swap");

        final Type p0 = popType();
        final Type p1 = popType();
        p0.swap(method, p1);

        pushType(p0);
        pushType(p1);
        debug("after ", p0, p1);
        return this;
    }

    /**
     * Add a local variable. This is a nop if the symbol has no slot
     *
     * @param symbol symbol for the local variable
     * @param start  start of scope
     * @param end    end of scope
     */
    public void localVariable(final Symbol symbol, final Label start, final Label end) {
        if (!symbol.hasSlot()) {
            return;
        }

        String name = symbol.getName();

        if (name.equals(THIS.tag())) {
            name = THIS_DEBUGGER.tag();
        }

        method.visitLocalVariable(name, symbol.getSymbolType().getDescriptor(), null, start, end, symbol.getSlot());
    }

    /**
     * Create a new string builder, call the constructor and push the instance to the stack.
     *
     * @return the method emitter
     */
    public MethodEmitter newStringBuilder() {
        return invoke(constructorNoLookup(StringBuilder.class)).dup();
    }

    /**
     * Pop a string and a StringBuilder from the top of the stack and call the append
     * function of the StringBuilder, appending the string. Pushes the StringBuilder to
     * the stack when finished.
     *
     * @return the method emitter
     */
    public MethodEmitter stringBuilderAppend() {
        convert(Type.STRING);
        return invoke(virtualCallNoLookup(StringBuilder.class, "append", StringBuilder.class, String.class));
    }

    /**
     * Associate a variable with a given range
     *
     * @param name  name of the variable
     * @param start start
     * @param end   end
     */
    public void markerVariable(final String name, final Label start, final Label end) {
        method.visitLocalVariable(name, Type.OBJECT.getDescriptor(), null, start, end, 0);
    }

    /**
     * Pops two integer types from the stack, performs a bitwise and and pushes
     * the result
     *
     * @return the method emitter
     */
    public MethodEmitter and() {
        debug("and");
        pushType(get2i().and(method));
        return this;
    }

    /**
     * Pops two integer types from the stack, performs a bitwise or and pushes
     * the result
     *
     * @return the method emitter
     */
    public MethodEmitter or() {
        debug("or");
        pushType(get2i().or(method));
        return this;
    }

    /**
     * Pops two integer types from the stack, performs a bitwise xor and pushes
     * the result
     *
     * @return the method emitter
     */
    public MethodEmitter xor() {
        debug("xor");
        pushType(get2i().xor(method));
        return this;
    }

    /**
     * Pops two integer types from the stack, performs a bitwise logic shift right and pushes
     * the result. The shift count, the first element, must be INT.
     *
     * @return the method emitter
     */
    public MethodEmitter shr() {
        debug("shr");
        popType(Type.INT);
        pushType(popInteger().shr(method));
        return this;
    }

    /**
     * Pops two integer types from the stack, performs a bitwise shift left and and pushes
     * the result. The shift count, the first element, must be INT.
     *
     * @return the method emitter
     */
    public MethodEmitter shl() {
        debug("shl");
        popType(Type.INT);
        pushType(popInteger().shl(method));
        return this;
    }

    /**
     * Pops two integer types from the stack, performs a bitwise arithetic shift right and pushes
     * the result. The shift count, the first element, must be INT.
     *
     * @return the method emitter
     */
    public MethodEmitter sar() {
        debug("sar");
        popType(Type.INT);
        pushType(popInteger().sar(method));
        return this;
    }

    /**
     * Pops a numeric type from the stack, negates it and pushes the result
     *
     * @return the method emitter
     */
    public MethodEmitter neg() {
        debug("neg");
        pushType(popNumeric().neg(method));
        return this;
    }

    /**
     * Add label for the start of a catch block and push the exception to the
     * stack
     *
     * @param recovery label pointing to start of catch block
     */
    public void _catch(final Label recovery) {
        stack.clear();
        stack.push(Type.OBJECT);
        label(recovery);
    }

    /**
     * Start a try/catch block.
     *
     * @param entry          start label for try
     * @param exit           end label for try
     * @param recovery       start label for catch
     * @param typeDescriptor type descriptor for exception
     */
    public void _try(final Label entry, final Label exit, final Label recovery, final String typeDescriptor) {
        method.visitTryCatchBlock(entry, exit, recovery, typeDescriptor);
    }

    /**
     * Start a try/catch block.
     *
     * @param entry    start label for try
     * @param exit     end label for try
     * @param recovery start label for catch
     * @param clazz    exception class
     */
    public void _try(final Label entry, final Label exit, final Label recovery, final Class<?> clazz) {
        method.visitTryCatchBlock(entry, exit, recovery, CompilerConstants.className(clazz));
    }

    /**
     * Start a try/catch block. The catch is "Throwable" - i.e. catch-all
     *
     * @param entry    start label for try
     * @param exit     end label for try
     * @param recovery start label for catch
     */
    public void _try(final Label entry, final Label exit, final Label recovery) {
        _try(entry, exit, recovery, (String)null);
    }


    /**
     * Load the constants array
     * @param unitClassName name of the compile unit from which to load constants
     * @return this method emitter
     */
    public MethodEmitter loadConstants(final String unitClassName) {
        getStatic(unitClassName, CONSTANTS.tag(), CONSTANTS.descriptor());
        assert peekType().isArray() : peekType();
        return this;
    }

    /**
     * Push the undefined value for the given type, i.e.
     * UNDEFINED or UNDEFINEDNUMBER. Currently we have no way of
     * representing UNDEFINED for INTs and LONGs, so they are not
     * allowed to be local variables (yet)
     *
     * @param type the type for which to push UNDEFINED
     * @return the method emitter
     */
    public MethodEmitter loadUndefined(final Type type) {
        debug("load undefined " + type);
        pushType(type.loadUndefined(method));
        return this;
    }

    /**
     * Push the empty value for the given type, i.e. EMPTY.
     *
     * @param type the type
     * @return the method emitter
     */
    public MethodEmitter loadEmpty(final Type type) {
        debug("load empty " + type);
        pushType(type.loadEmpty(method));
        return this;
    }

    /**
     * Push null to stack
     *
     * @return the method emitter
     */
    public MethodEmitter loadNull() {
        debug("aconst_null");
        pushType(Type.OBJECT.ldc(method, null));
        return this;
    }

    /**
     * Push a handle representing this class top stack
     *
     * @param className name of the class
     *
     * @return the method emitter
     */
    public MethodEmitter loadType(final String className) {
        debug("load type", className);
        method.visitLdcInsn(jdk.internal.org.objectweb.asm.Type.getObjectType(className));
        pushType(Type.OBJECT);
        return this;
    }

    /**
     * Push a boolean constant to the stack.
     *
     * @param b value of boolean
     *
     * @return the method emitter
     */
    public MethodEmitter load(final boolean b) {
        debug("load boolean", b);
        pushType(Type.BOOLEAN.ldc(method, b));
        return this;
    }

    /**
     * Push an int constant to the stack
     *
     * @param i value of the int
     *
     * @return the method emitter
     */
    public MethodEmitter load(final int i) {
        debug("load int", i);
        pushType(Type.INT.ldc(method, i));
        return this;
    }

    /**
     * Push a double constant to the stack
     *
     * @param d value of the double
     *
     * @return the method emitter
     */
    public MethodEmitter load(final double d) {
        debug("load double", d);
        pushType(Type.NUMBER.ldc(method, d));
        return this;
    }

    /**
     * Push an long constant to the stack
     *
     * @param l value of the long
     *
     * @return the method emitter
     */
    public MethodEmitter load(final long l) {
        debug("load long", l);
        pushType(Type.LONG.ldc(method, l));
        return this;
    }

    /**
     * Fetch the length of an array.
     * @return Array length.
     */
    public MethodEmitter arraylength() {
        debug("arraylength");
        popType(Type.OBJECT);
        pushType(Type.OBJECT_ARRAY.arraylength(method));
        return this;
    }

    /**
     * Push a String constant to the stack
     *
     * @param s value of the String
     *
     * @return the method emitter
     */
    public MethodEmitter load(final String s) {
        debug("load string", s);

        if (s == null) {
            loadNull();
            return this;
        }

        //NASHORN-142 - split too large string
        final int length = s.length();
        if (length > LARGE_STRING_THRESHOLD) {

            _new(StringBuilder.class);
            dup();
            load(length);
            invoke(constructorNoLookup(StringBuilder.class, int.class));

            for (int n = 0; n < length; n += LARGE_STRING_THRESHOLD) {
                final String part = s.substring(n, Math.min(n + LARGE_STRING_THRESHOLD, length));
                load(part);
                stringBuilderAppend();
            }

            invoke(virtualCallNoLookup(StringBuilder.class, "toString", String.class));

            return this;
        }

        pushType(Type.OBJECT.ldc(method, s));
        return this;
    }

    /**
     * Push an local variable to the stack. If the symbol representing
     * the local variable doesn't have a slot, this is a NOP
     *
     * @param symbol the symbol representing the local variable.
     *
     * @return the method emitter
     */
    public MethodEmitter load(final Symbol symbol) {
        assert symbol != null;
        if (symbol.hasSlot()) {
            debug("load symbol", symbol.getName() + " slot=" + symbol.getSlot());
            pushType(symbol.getSymbolType().load(method, symbol.getSlot()));
        }
        return this;
    }

    /**
     * Push a local variable to the stack, given an explicit bytecode slot
     * This is used e.g. for stub generation where we know where items like
     * "this" and "scope" reside.
     *
     * @param type  the type of the variable
     * @param slot  the slot the variable is in
     *
     * @return the method emitter
     */
    public MethodEmitter load(final Type type, final int slot) {
        debug("explicit load", type, slot);
        pushType(type.load(method, slot));
        return this;
    }

    /**
     * Push the this object to the stack.
     *
     * @return the method emitter
     */
    public MethodEmitter loadThis() {
        load(functionNode.getThisNode().getSymbol());
        return this;
    }

    /**
     * Push the scope object to the stack.
     *
     * @return the method emitter
     */
    public MethodEmitter loadScope() {
        if (peekType() == Type.SCOPE) {
            dup();
            return this;
        }
        load(functionNode.getScopeNode().getSymbol());
        return this;
    }

    /**
     * Push the return object to the stack.
     *
     * @return the method emitter
     */
    public MethodEmitter loadResult() {
        load(functionNode.getResultNode().getSymbol());
        return this;
    }


    /**
     * Push a method handle to the stack
     *
     * @param className  class name
     * @param methodName method name
     * @param descName   descriptor
     * @param flags      flags that describe this handle, e.g. invokespecial new, or invoke virtual
     *
     * @see ClassEmitter.Flag
     *
     * @return the method emitter
     */
    public MethodEmitter loadHandle(final String className, final String methodName, final String descName, final EnumSet<Flag> flags) {
        debug("load handle ");
        pushType(Type.OBJECT.ldc(method, new Handle(Flag.getValue(flags), className, methodName, descName)));
        return this;
    }

    /**
     * Push the varargs object to the stack
     *
     * @return the method emitter
     */
    public MethodEmitter loadVarArgs() {
        debug("load var args " + functionNode.getVarArgsNode().getSymbol());
        return load(functionNode.getVarArgsNode().getSymbol());
    }

    /**
     * Push the arguments array to the stack
     *
     * @return the method emitter
     */
    public MethodEmitter loadArguments() {
        debug("load arguments " + functionNode.getVarArgsNode().getSymbol());
        assert functionNode.getArgumentsNode().getSymbol().getSlot() != 0;
        return load(functionNode.getArgumentsNode().getSymbol());
    }

    /**
     * Push the callee object to the stack
     *
     * @return the method emitter
     */
    public MethodEmitter loadCallee() {
        debug("load callee " + functionNode.getCalleeNode().getSymbol());
        assert functionNode.getCalleeNode().getSymbol().getSlot() != 0;

        return load(functionNode.getCalleeNode().getSymbol());
    }

    /**
     * Pop the scope from the stack and store it in its predefined slot
     */
    public void storeScope() {
        debug("store scope");
        store(functionNode.getScopeNode().getSymbol());
    }

    /**
     * Pop the return from the stack and store it in its predefined slot
     */
    public void storeResult() {
        debug("store result");
        store(functionNode.getResultNode().getSymbol());
    }

    /**
     * Pop the arguments array from the stack and store it in its predefined slot
     */
    public void storeArguments() {
        debug("store arguments");
        store(functionNode.getArgumentsNode().getSymbol());
    }

    /**
     * Load an element from an array, determining type automatically
     * @return the method emitter
     */
    public MethodEmitter arrayload() {
        debug("Xaload");
        popType(Type.INT);
        pushType(popArray().aload(method));
        return this;
    }

    /**
     * Pop a value, an index and an array from the stack and store
     * the value at the given index in the array.
     */
    public void arraystore() {
        debug("Xastore");
        final Type value = popType();
        final Type index = popType(Type.INT);
        assert index.isInteger() : "array index is not integer, but " + index;
        final ArrayType array = popArray();

        assert value.isEquivalentTo(array.getElementType()) : "Storing "+value+" into "+array;
        assert array.isObject();
        array.astore(method);
    }

    /**
     * Pop a value from the stack and store it in a local variable represented
     * by the given symbol. If the symbol has no slot, this is a NOP
     *
     * @param symbol symbol to store stack to
     */
    public void store(final Symbol symbol) {
        assert symbol != null;
        if (symbol.hasSlot()) {
            debug("store", symbol);
            popType(symbol.getSymbolType()).store(method, symbol.getSlot());
        }
    }

    /**
     * Pop a value from the stack and store it in a given local variable
     * slot.
     *
     * @param type the type to pop
     * @param slot the slot
     */
    public void store(final Type type, final int slot) {
        popType(type);
        type.store(method, slot);
    }

    /**
     * Increment/Decrement a local integer by the given value.
     *
     * @param slot the int slot
     * @param increment the amount to increment
     */
    public void iinc(final int slot, final int increment) {
        debug("iinc");
        method.visitIincInsn(slot, increment);
    }

    /**
     * Pop an exception object from the stack and generate code
     * for throwing it
     */
    public void athrow() {
        debug("athrow");
        final Type receiver = popType(Type.OBJECT);
        assert receiver.isObject();
        method.visitInsn(ATHROW);
        stack = null;
    }

    /**
     * Pop an object from the stack and perform an instanceof
     * operation, given a classDescriptor to compare it to.
     * Push the boolean result 1/0 as an int to the stack
     *
     * @param classDescriptor descriptor of the class to type check against
     *
     * @return the method emitter
     */
    public MethodEmitter _instanceof(final String classDescriptor) {
        debug("instanceof", classDescriptor);
        popType(Type.OBJECT);
        method.visitTypeInsn(INSTANCEOF, classDescriptor);
        pushType(Type.INT);
        return this;
    }

    /**
     * Pop an object from the stack and perform an instanceof
     * operation, given a classDescriptor to compare it to.
     * Push the boolean result 1/0 as an int to the stack
     *
     * @param clazz the type to check instanceof against
     *
     * @return the method emitter
     */
    public MethodEmitter _instanceof(final Class<?> clazz) {
        return _instanceof(CompilerConstants.className(clazz));
    }

    /**
     * Perform a checkcast operation on the object at the top of the
     * stack.
     *
     * @param classDescriptor descriptor of the class to type check against
     *
     * @return the method emitter
     */
    public MethodEmitter checkcast(final String classDescriptor) {
        debug("checkcast", classDescriptor);
        assert peekType().isObject();
        method.visitTypeInsn(CHECKCAST, classDescriptor);
        return this;
    }

    /**
     * Perform a checkcast operation on the object at the top of the
     * stack.
     *
     * @param clazz class to checkcast against
     *
     * @return the method emitter
     */
    public MethodEmitter checkcast(final Class<?> clazz) {
        return checkcast(CompilerConstants.className(clazz));
    }

    /**
     * Instantiate a new array given a length that is popped
     * from the stack and the array type
     *
     * @param arrayType the type of the array
     *
     * @return the method emitter
     */
    public MethodEmitter newarray(final ArrayType arrayType) {
        debug("newarray ", "arrayType=" + arrayType);
        popType(Type.INT); //LENGTH
        pushType(arrayType.newarray(method));
        return this;
    }

    /**
     * Instantiate a multidimensional array with a given number of dimensions.
     * On the stack are dim lengths of the sub arrays.
     *
     * @param arrayType type of the array
     * @param dims      number of dimensions
     *
     * @return the method emitter
     */
    public MethodEmitter multinewarray(final ArrayType arrayType, final int dims) {
        debug("multianewarray ", arrayType, dims);
        for (int i = 0; i < dims; i++) {
            popType(Type.INT); //LENGTH
        }
        pushType(arrayType.newarray(method, dims));
        return this;
    }

    /**
     * Helper function to pop and type check the appropriate arguments
     * from the stack given a method signature
     *
     * @param signature method signature
     *
     * @return return type of method
     */
    private Type fixParamStack(final String signature) {
        final Type[] params = Type.getMethodArguments(signature);
        for (int i = params.length - 1; i >= 0; i--) {
            popType(params[i]);
        }
        final Type returnType = Type.getMethodReturnType(signature);
        return returnType;
    }

    /**
     * Generate an invocation to a Call structure
     * @see CompilerConstants
     *
     * @param call the call object
     *
     * @return the method emitter
     */
    public MethodEmitter invoke(final Call call) {
        return call.invoke(this);
    }

    private MethodEmitter invoke(final int opcode, final String className, final String methodName, final String methodDescriptor, final boolean hasReceiver) {
        final Type returnType = fixParamStack(methodDescriptor);

        if (hasReceiver) {
            popType(Type.OBJECT);
        }

        method.visitMethodInsn(opcode, className, methodName, methodDescriptor);

        if (returnType != null) {
            pushType(returnType);
        }

        return this;
    }

    /**
     * Pop receiver from stack, perform an invoke special
     *
     * @param className        class name
     * @param methodName       method name
     * @param methodDescriptor descriptor
     *
     * @return the method emitter
     */
    public MethodEmitter invokeSpecial(final String className, final String methodName, final String methodDescriptor) {
        debug("invokespecial", className + "." + methodName + methodDescriptor);
        return invoke(INVOKESPECIAL, className, methodName, methodDescriptor, true);
    }

    /**
     * Pop receiver from stack, perform an invoke virtual, push return value if any
     *
     * @param className        class name
     * @param methodName       method name
     * @param methodDescriptor descriptor
     *
     * @return the method emitter
     */
    public MethodEmitter invokeVirtual(final String className, final String methodName, final String methodDescriptor) {
        debug("invokevirtual", className + "." + methodName + methodDescriptor + " " + stack);
        return invoke(INVOKEVIRTUAL, className, methodName, methodDescriptor, true);
    }

    /**
     * Perform an invoke static and push the return value if any
     *
     * @param className        class name
     * @param methodName       method name
     * @param methodDescriptor descriptor
     *
     * @return the method emitter
     */
    public MethodEmitter invokeStatic(final String className, final String methodName, final String methodDescriptor) {
        debug("invokestatic", className + "." + methodName + methodDescriptor);
        invoke(INVOKESTATIC, className, methodName, methodDescriptor, false);
        return this;
    }

    /**
     * Perform an invoke static and replace the return type if we know better, e.g. Global.allocate
     * that allocates an array should return an ObjectArray type as a NativeArray counts as that
     *
     * @param className        class name
     * @param methodName       method name
     * @param methodDescriptor descriptor
     * @param returnType       return type override
     *
     * @return the method emitter
     */
    public MethodEmitter invokeStatic(final String className, final String methodName, final String methodDescriptor, final Type returnType) {
        invokeStatic(className, methodName, methodDescriptor);
        popType();
        pushType(returnType);
        return this;
    }

    /**
     * Pop receiver from stack, perform an invoke interface and push return value if any
     *
     * @param className        class name
     * @param methodName       method name
     * @param methodDescriptor descriptor
     *
     * @return the method emitter
     */
    public MethodEmitter invokeInterface(final String className, final String methodName, final String methodDescriptor) {
        debug("invokeinterface", className + "." + methodName + methodDescriptor);
        return invoke(INVOKEINTERFACE, className, methodName, methodDescriptor, true);
    }

    /**
     * Generate a lookup switch, popping the switch value from the stack
     *
     * @param defaultLabel default label
     * @param values       case values for the table
     * @param table        default label
     */
    public void lookupSwitch(final Label defaultLabel, final int[] values, final Label[] table) {
        debug("lookupswitch", peekType());
        popType(Type.INT);
        method.visitLookupSwitchInsn(defaultLabel, values, table);
    }

    /**
     * Generate a table switch
     * @param lo            low value
     * @param hi            high value
     * @param defaultLabel  default label
     * @param table         label table
     */
    public void tableSwitch(final int lo, final int hi, final Label defaultLabel, final Label[] table) {
        debug("tableswitch", peekType());
        popType(Type.INT);
        method.visitTableSwitchInsn(lo, hi, defaultLabel, table);
    }

    /**
     * Abstraction for performing a conditional jump of any type
     *
     * @see MethodEmitter.Condition
     *
     * @param cond      the condition to test
     * @param trueLabel the destination label is condition is true
     */
    void conditionalJump(final Condition cond, final Label trueLabel) {
        conditionalJump(cond, cond != Condition.GT && cond != Condition.GE, trueLabel);
    }

    /**
     * Abstraction for performing a conditional jump of any type,
     * including a dcmpg/dcmpl semantic for doubles.
     *
     * @param cond      the condition to test
     * @param isCmpG    is this a dcmpg for numbers, false if it's a dcmpl
     * @param trueLabel the destination label if condition is true
     */
    void conditionalJump(final Condition cond, final boolean isCmpG, final Label trueLabel) {
        if (peekType().isCategory2()) {
            debug("[ld]cmp isCmpG=" + isCmpG);
            pushType(get2n().cmp(method, isCmpG));
            jump(Condition.toUnary(cond), trueLabel, 1);
        } else {
            debug("if" + cond);
            jump(Condition.toBinary(cond, peekType().isObject()), trueLabel, 2);
        }
    }

    /**
     * Perform a non void return, popping the type from the stack
     *
     * @param type the type for the return
     */
    public void _return(final Type type) {
        debug("return", type);
        assert stack.size() == 1 : "Only return value on stack allowed at return point - depth=" + stack.size() + " stack = " + stack;
        final Type stackType = peekType();
        if (!Type.areEquivalent(type, stackType)) {
            convert(type);
        }
        popType(type)._return(method);
        stack = null;
    }

    /**
     * Perform a return using the stack top value as the guide for the type
     */
    public void _return() {
        _return(peekType());
    }

    /**
     * Perform a void return.
     */
    public void returnVoid() {
        debug("return [void]");
        assert stack.isEmpty() : stack;
        method.visitInsn(RETURN);
        stack = null;
    }

    /**
     * Goto, possibly when splitting is taking place. If
     * a splitNode exists, we need to handle the case that the
     * jump target is another method
     *
     * @param label destination label
     */
    public void splitAwareGoto(final Label label) {

        if (splitNode != null) {
            final int index = splitNode.getExternalTargets().indexOf(label);

            if (index > -1) {
                loadScope();
                checkcast(Scope.class);
                load(index + 1);
                invoke(Scope.SET_SPLIT_STATE);
                loadUndefined(Type.OBJECT);
                _return(functionNode.getReturnType());
                return;
            }
        }

        _goto(label);
    }

    /**
     * Perform a comparison of two number types that are popped from the stack
     *
     * @param isCmpG is this a dcmpg semantic, false if it's a dcmpl semantic
     *
     * @return the method emitter
     */
    public MethodEmitter cmp(final boolean isCmpG) {
        pushType(get2n().cmp(method, isCmpG));
        return this;
    }

    /**
     * Helper function for jumps, conditional or not
     * @param opcode  opcode for jump
     * @param label   destination
     * @param n       elements on stack to compare, 0-2
     */
    private void jump(final int opcode, final Label label, final int n) {
        for (int i = 0; i < n; i++) {
            assert peekType().isInteger() || peekType().isBoolean() || peekType().isObject() : "expecting integer type or object for jump, but found " + peekType();
            popType();
        }
        mergeStackTo(label);
        method.visitJumpInsn(opcode, label);
    }

    /**
     * Generate an if_acmpeq
     *
     * @param label label to true case
     */
    public void if_acmpeq(final Label label) {
        debug("if_acmpeq", label);
        jump(IF_ACMPEQ, label, 2);
    }

    /**
     * Generate an if_acmpne
     *
     * @param label label to true case
     */
    public void if_acmpne(final Label label) {
        debug("if_acmpne", label);
        jump(IF_ACMPNE, label, 2);
    }

    /**
     * Generate an ifnull
     *
     * @param label label to true case
     */
    public void ifnull(final Label label) {
        debug("ifnull", label);
        jump(IFNULL, label, 1);
    }

    /**
     * Generate an ifnonnull
     *
     * @param label label to true case
     */
    public void ifnonnull(final Label label) {
        debug("ifnonnull", label);
        jump(IFNONNULL, label, 1);
    }

    /**
     * Generate an ifeq
     *
     * @param label label to true case
     */
    public void ifeq(final Label label) {
        debug("ifeq ", label);
        jump(IFEQ, label, 1);
    }

    /**
     * Generate an if_icmpeq
     *
     * @param label label to true case
     */
    public void if_icmpeq(final Label label) {
        debug("if_icmpeq", label);
        jump(IF_ICMPEQ, label, 2);
    }

    /**
     * Generate an if_ne
     *
     * @param label label to true case
     */

    public void ifne(final Label label) {
        debug("ifne", label);
        jump(IFNE, label, 1);
    }

    /**
     * Generate an if_icmpne
     *
     * @param label label to true case
     */
    public void if_icmpne(final Label label) {
        debug("if_icmpne", label);
        jump(IF_ICMPNE, label, 2);
    }

    /**
     * Generate an iflt
     *
     * @param label label to true case
     */
    public void iflt(final Label label) {
        debug("iflt", label);
        jump(IFLT, label, 1);
    }

    /**
     * Generate an ifle
     *
     * @param label label to true case
     */
    public void ifle(final Label label) {
        debug("ifle", label);
        jump(IFLE, label, 1);
    }

    /**
     * Generate an ifgt
     *
     * @param label label to true case
     */
    public void ifgt(final Label label) {
        debug("ifgt", label);
        jump(IFGT, label, 1);
    }

    /**
     * Generate an ifge
     *
     * @param label label to true case
     */
    public void ifge(final Label label) {
        debug("ifge", label);
        jump(IFGE, label, 1);
    }

    /**
     * Unconditional jump to a label
     *
     * @param label destination label
     */
    public void _goto(final Label label) {
        debug("goto", label);
        jump(GOTO, label, 0);
        stack = null;
    }

    /**
     * Examine two stacks and make sure they are of the same size and their
     * contents are equivalent to each other
     * @param s0 first stack
     * @param s1 second stack
     *
     * @return true if stacks are equivalent, false otherwise
     */
    private boolean stacksEquivalent(final ArrayDeque<Type> s0, final ArrayDeque<Type> s1) {
        if (s0.size() != s1.size()) {
            debug("different stack sizes", s0, s1);
            return false;
        }

        final Type[] s0a = s0.toArray(new Type[s0.size()]);
        final Type[] s1a = s1.toArray(new Type[s1.size()]);
        for (int i = 0; i < s0.size(); i++) {
            if (!s0a[i].isEquivalentTo(s1a[i])) {
                debug("different stack element", s0a[i], s1a[i]);
                return false;
            }
        }

        return true;
    }

    /**
     * A join in control flow - helper function that makes sure all entry stacks
     * discovered for the join point so far are equivalent
     * @param label
     */
    private void mergeStackTo(final Label label) {
        final ArrayDeque<Type> labelStack = label.getStack();
        //debug(labelStack == null ? " >> Control flow - first visit " + label : " >> Control flow - JOIN with " + labelStack + " at " + label);
        if (labelStack == null) {
            assert stack != null;
            label.setStack(stack.clone());
            return;
        }
        assert stacksEquivalent(stack, labelStack);
    }

    /**
     * Register a new label, enter it here.
     *
     * @param label the label
     */
    public void label(final Label label) {
        /*
         * If stack == null, this means that we came here not through a fallthrough.
         * E.g. a label after an athrow. Then we create a new stack if one doesn't exist
         * for this location already.
         */
        if (stack == null) {
            stack = label.getStack();
            if (stack == null) {
                stack = new ArrayDeque<>(); //we don't have a stack at this point.
            }
        }
        debug_label(label);

        mergeStackTo(label); //we have to merge our stack to whatever is in the label

        method.visitLabel(label);
    }

    /**
     * Pop element from stack, convert to given type
     *
     * @param to type to convert to
     *
     * @return the method emitter
     */
    public MethodEmitter convert(final Type to) {
        final Type type = peekType().convert(method, to);
        if (type != null) {
            if (peekType() != to) {
                debug("convert", peekType(), "->", to);
            }
            popType();
            pushType(type);
        }
        return this;
    }

    /**
     * Helper function - expect two types that are equivalent
     *
     * @return common type
     */
    private Type get2() {
        final Type p0 = popType();
        final Type p1 = popType();
        assert p0.isEquivalentTo(p1) : "expecting equivalent types on stack but got " + p0 + " and " + p1;
        return p0;
    }

    /**
     * Helper function - expect two types that are integer types and equivalent
     *
     * @return common type
     */
    private BitwiseType get2i() {
        final BitwiseType p0 = popInteger();
        final BitwiseType p1 = popInteger();
        assert p0.isEquivalentTo(p1) : "expecting equivalent types on stack but got " + p0 + " and " + p1;
        return p0;
    }

    /**
     * Helper function - expect two types that are numbers and equivalent
     *
     * @return common type
     */
    private NumericType get2n() {
        final NumericType p0 = popNumeric();
        final NumericType p1 = popNumeric();
        assert p0.isEquivalentTo(p1) : "expecting equivalent types on stack but got " + p0 + " and " + p1;
        return p0;
    }

    /**
     * Pop two numbers, perform addition and push result
     *
     * @return the method emitter
     */
    public MethodEmitter add() {
        debug("add");
        pushType(get2().add(method));
        return this;
    }

    /**
     * Pop two numbers, perform subtraction and push result
     *
     * @return the method emitter
     */
    public MethodEmitter sub() {
        debug("sub");
        pushType(get2n().sub(method));
        return this;
    }

    /**
     * Pop two numbers, perform multiplication and push result
     *
     * @return the method emitter
     */
    public MethodEmitter mul() {
        debug("mul ");
        pushType(get2n().mul(method));
        return this;
    }

    /**
     * Pop two numbers, perform division and push result
     *
     * @return the method emitter
     */
    public MethodEmitter div() {
        debug("div");
        pushType(get2n().div(method));
        return this;
    }

    /**
     * Pop two numbers, calculate remainder and push result
     *
     * @return the method emitter
     */
    public MethodEmitter rem() {
        debug("rem");
        pushType(get2n().rem(method));
        return this;
    }

    /**
     * Retrieve the top <tt>count</tt> types on the stack without modifying it.
     *
     * @param count number of types to return
     * @return array of Types
     */
    protected Type[] getTypesFromStack(final int count) {
        final Iterator<Type> iter  = stack.iterator();
        final Type[]         types = new Type[count];

        for (int i = count - 1; i >= 0; i--) {
            types[i] = iter.next();
        }

        return types;
    }

    /**
     * Helper function to generate a function signature based on stack contents
     * and argument count and return type
     *
     * @param returnType return type
     * @param argCount   argument count
     *
     * @return function signature for stack contents
     */
    private String getDynamicSignature(final Type returnType, final int argCount, final boolean hasSelf) {
        final Iterator<Type> iter     = stack.iterator();
        final Type[]         argTypes = new Type[argCount];

        for (int i = argCount - 1; i >= 0; i--) {
            argTypes[i] = iter.next();
        }

        final FunctionSignature sig = new FunctionSignature(hasSelf, false, returnType, argTypes);
        for (int i = 0; i < argCount; i++) {
            popType(argTypes[argCount - i - 1]);
        }
        for (int i = 0 ; i < sig.size() - argTypes.length; i++) {
            popType(Type.OBJECT);
        }

        return sig.toString();
    }

    /**
     * Generate a dynamic new
     *
     * @param argCount  number of arguments
     * @param flags     callsite flags
     *
     * @return the method emitter
     */
    public MethodEmitter dynamicNew(final int argCount, final int flags) {
        debug("dynamic_new", "argcount=" + argCount);
        final String signature = getDynamicSignature(Type.OBJECT, argCount, true);
        method.visitInvokeDynamicInsn("dyn:new", signature, LINKERBOOTSTRAP, flags);
        pushType(Type.OBJECT); //TODO fix result type
        return this;
    }

    /**
     * Generate a dynamic call
     *
     * @param returnType return type
     * @param argCount   number of arguments
     * @param flags      callsite flags
     *
     * @return the method emitter
     */
    public MethodEmitter dynamicCall(final Type returnType, final int argCount, final int flags) {
        debug("dynamic_call", "args=" + argCount, "returnType=" + returnType);
        final String signature = getDynamicSignature(returnType, argCount + 1, true);
        debug("   signature", signature);
        method.visitInvokeDynamicInsn("dyn:call", signature, LINKERBOOTSTRAP, flags);
        pushType(returnType);

        return this;
    }

    /**
     * Generate a dynamic call for a runtime node
     *
     * @param name       tag for the invoke dynamic for this runtime node
     * @param returnType return type
     * @param request    RuntimeNode request
     *
     * @return the method emitter
     */
    public MethodEmitter dynamicRuntimeCall(final String name, final Type returnType, final RuntimeNode.Request request) {
        debug("dynamic_runtime_call", name, "args=" + request.getArity(), "returnType=" + returnType);
        final String signature = getDynamicSignature(returnType, request.getArity(), false);
        debug("   signature", signature);
        method.visitInvokeDynamicInsn(name, signature, RUNTIMEBOOTSTRAP);
        pushType(returnType);

        return this;
    }

    /**
     * Generate dynamic getter. Pop scope from stack. Push result
     *
     * @param valueType type of the value to set
     * @param name      name of property
     * @param flags     call site flags
     * @param isMethod  should it prefer retrieving methods
     *
     * @return the method emitter
     */
    public MethodEmitter dynamicGet(final Type valueType, final String name, final int flags, final boolean isMethod) {
        debug("dynamic_get", name, valueType);

        Type type = valueType;
        if (type.isObject() || type.isBoolean()) {
            type = Type.OBJECT; //promote e.g strings to object generic setter
        }

        popType(Type.SCOPE);
        method.visitInvokeDynamicInsn((isMethod ? "dyn:getMethod|getProp|getElem:" : "dyn:getProp|getElem|getMethod:") +
                NameCodec.encode(name), Type.getMethodDescriptor(type, Type.OBJECT), LINKERBOOTSTRAP, flags);

        pushType(type);

        convert(valueType); //most probably a nop

        return this;
    }

    /**
     * Generate dynamic setter. Pop receiver and property from stack.
     *
     * @param valueType the type of the value to set
     * @param name      name of property
     * @param flags     call site flags
     */
     public void dynamicSet(final Type valueType, final String name, final int flags) {
        debug("dynamic_set", name, peekType());

        Type type = valueType;
        if (type.isObject() || type.isBoolean()) { //promote strings to objects etc
            type = Type.OBJECT;
            convert(Type.OBJECT); //TODO bad- until we specialize boolean setters,
        }

        popType(type);
        popType(Type.SCOPE);

        method.visitInvokeDynamicInsn("dyn:setProp|setElem:" + NameCodec.encode(name), methodDescriptor(void.class, Object.class, type.getTypeClass()), LINKERBOOTSTRAP, flags);
    }

     /**
     * Dynamic getter for indexed structures. Pop index and receiver from stack,
     * generate appropriate signatures based on types
     *
     * @param result result type for getter
     * @param flags call site flags for getter
     * @param isMethod should it prefer retrieving methods
     *
     * @return the method emitter
     */
    public MethodEmitter dynamicGetIndex(final Type result, final int flags, final boolean isMethod) {
        debug("dynamic_get_index", peekType(1) + "[" + peekType() + "]");

        Type resultType = result;
        if (result.isBoolean()) {
            resultType = Type.OBJECT; // INT->OBJECT to avoid another dimension of cross products in the getters. TODO
        }

        Type index = peekType();
        if (index.isObject() || index.isBoolean()) {
            index = Type.OBJECT; //e.g. string->object
            convert(Type.OBJECT);
        }
        popType();

        popType(Type.OBJECT);

        final String signature = Type.getMethodDescriptor(resultType, Type.OBJECT /*e.g STRING->OBJECT*/, index);

        method.visitInvokeDynamicInsn(isMethod ? "dyn:getMethod|getElem|getProp" : "dyn:getElem|getProp|getMethod",
                signature, LINKERBOOTSTRAP, flags);
        pushType(resultType);

        if (result.isBoolean()) {
            convert(Type.BOOLEAN);
        }

        return this;
    }

    /**
     * Dynamic setter for indexed structures. Pop value, index and receiver from
     * stack, generate appropriate signature based on types
     *
     * @param flags call site flags for setter
     */
    public void dynamicSetIndex(final int flags) {
        debug("dynamic_set_index", peekType(2) + "[" + peekType(1) + "] =", peekType());

        Type value = peekType();
        if (value.isObject() || value.isBoolean()) {
            value = Type.OBJECT; //e.g. STRING->OBJECT - one descriptor for all object types
            convert(Type.OBJECT);
        }
        popType();

        Type index = peekType();
        if (index.isObject() || index.isBoolean()) {
            index = Type.OBJECT; //e.g. string->object
            convert(Type.OBJECT);
        }
        popType(index);

        final Type receiver = popType(Type.OBJECT);
        assert receiver.isObject();

        method.visitInvokeDynamicInsn("dyn:setElem|setProp", methodDescriptor(void.class, receiver.getTypeClass(), index.getTypeClass(), value.getTypeClass()), LINKERBOOTSTRAP, flags);
    }

    /**
     * Load a key value in the proper form.
     *
     * @param key
     */
    //TODO move this and break it apart
    MethodEmitter loadKey(final Object key) {
        if (key instanceof IdentNode) {
            method.visitLdcInsn(((IdentNode) key).getName());
        } else if (key instanceof LiteralNode) {
            method.visitLdcInsn(((LiteralNode<?>)key).getString());
        } else {
            method.visitLdcInsn(JSType.toString(key));
        }
        pushType(Type.OBJECT); //STRING
        return this;
    }

     @SuppressWarnings("fallthrough")
     private static Type fieldType(final String desc) {
         switch (desc) {
         case "Z":
         case "B":
         case "C":
         case "S":
         case "I":
             return Type.INT;
         case "F":
             assert false;
         case "D":
             return Type.NUMBER;
         case "J":
             return Type.LONG;
         default:
             assert desc.startsWith("[") || desc.startsWith("L") : desc + " is not an object type";
             switch (desc.charAt(0)) {
             case 'L':
                 return Type.OBJECT;
             case '[':
                 return Type.typeFor(Array.newInstance(fieldType(desc.substring(1)).getTypeClass(), 0).getClass());
             default:
                 assert false;
             }
             return Type.OBJECT;
         }
     }

     /**
      * Generate get for a field access
      *
      * @param fa the field access
      *
      * @return the method emitter
      */
    public MethodEmitter getField(final FieldAccess fa) {
        return fa.get(this);
    }

     /**
      * Generate set for a field access
      *
      * @param fa the field access
      */
    public void putField(final FieldAccess fa) {
        fa.put(this);
    }

    /**
     * Get the value of a non-static field, pop the receiver from the stack,
     * push value to the stack
     *
     * @param className        class
     * @param fieldName        field name
     * @param fieldDescriptor  field descriptor
     *
     * @return the method emitter
     */
    public MethodEmitter getField(final String className, final String fieldName, final String fieldDescriptor) {
        debug("getfield", "receiver=" + peekType(), className + "." + fieldName + fieldDescriptor);
        final Type receiver = popType();
        assert receiver.isObject();
        method.visitFieldInsn(GETFIELD, className, fieldName, fieldDescriptor);
        pushType(fieldType(fieldDescriptor));
        return this;
    }

    /**
     * Get the value of a static field, push it to the stack
     *
     * @param className        class
     * @param fieldName        field name
     * @param fieldDescriptor  field descriptor
     *
     * @return the method emitter
     */
    public MethodEmitter getStatic(final String className, final String fieldName, final String fieldDescriptor) {
        debug("getstatic", className + "." + fieldName + "." + fieldDescriptor);
        method.visitFieldInsn(GETSTATIC, className, fieldName, fieldDescriptor);
        pushType(fieldType(fieldDescriptor));
        return this;
    }

    /**
     * Pop value and field from stack and write to a non-static field
     *
     * @param className       class
     * @param fieldName       field name
     * @param fieldDescriptor field descriptor
     */
    public void putField(final String className, final String fieldName, final String fieldDescriptor) {
        debug("putfield", "receiver=" + peekType(1), "value=" + peekType());
        popType(fieldType(fieldDescriptor));
        popType(Type.OBJECT);
        method.visitFieldInsn(PUTFIELD, className, fieldName, fieldDescriptor);
    }

    /**
     * Pop value from stack and write to a static field
     *
     * @param className       class
     * @param fieldName       field name
     * @param fieldDescriptor field descriptor
     */
    public void putStatic(final String className, final String fieldName, final String fieldDescriptor) {
        debug("putfield", "value=" + peekType());
        popType(fieldType(fieldDescriptor));
        method.visitFieldInsn(PUTSTATIC, className, fieldName, fieldDescriptor);
    }

    /**
     * Register line number at a label
     *
     * @param line  line number
     * @param label label
     */
    public void lineNumber(final int line, final Label label) {
        method.visitLineNumber(line, label);
    }

    /*
     * Debugging below
     */

    private final FieldAccess ERR_STREAM       = staticField(System.class, "err", PrintStream.class);
    private final Call        PRINT            = virtualCallNoLookup(PrintStream.class, "print", void.class, Object.class);
    private final Call        PRINTLN          = virtualCallNoLookup(PrintStream.class, "println", void.class, Object.class);
    private final Call        PRINT_STACKTRACE = virtualCallNoLookup(Throwable.class, "printStackTrace", void.class);

    /**
     * Emit a System.err.print statement of whatever is on top of the bytecode stack
     */
     public void print() {
         getField(ERR_STREAM);
         swap();
         convert(Type.OBJECT);
         invoke(PRINT);
     }

    /**
     * Emit a System.err.println statement of whatever is on top of the bytecode stack
     */
     public void println() {
         getField(ERR_STREAM);
         swap();
         convert(Type.OBJECT);
         invoke(PRINTLN);
     }

     /**
      * Emit a System.err.print statement
      * @param string string to print
      */
     public void print(final String string) {
         getField(ERR_STREAM);
         load(string);
         invoke(PRINT);
     }

     /**
      * Emit a System.err.println statement
      * @param string string to print
      */
     public void println(final String string) {
         getField(ERR_STREAM);
         load(string);
         invoke(PRINTLN);
     }

     /**
      * Print a stacktrace to S
      */
     public void stacktrace() {
         _new(Throwable.class);
         dup();
         invoke(constructorNoLookup(Throwable.class));
         invoke(PRINT_STACKTRACE);
     }

    private static int linePrefix = 0;

    /**
     * Debug function that outputs generated bytecode and stack contents
     *
     * @param args debug information to print
     */
    private void debug(final Object... args) {
        debug(30, args);
    }

    /**
     * Debug function that outputs generated bytecode and stack contents
     * for a label - indentation is currently the only thing that differs
     *
     * @param args debug information to print
     */
    private void debug_label(final Object... args) {
        debug(26, args);
    }

    private void debug(final int padConstant, final Object... args) {
        if (DEBUG) {
            final StringBuilder sb = new StringBuilder();
            int pad;

            sb.append('#');
            sb.append(++linePrefix);

            pad = 5 - sb.length();
            while (pad > 0) {
                sb.append(' ');
                pad--;
            }

            if (!stack.isEmpty()) {
                sb.append("{");
                sb.append(stack.size());
                sb.append(":");
                for (final Iterator<Type> iter = stack.iterator(); iter.hasNext();) {
                    final Type t = iter.next();

                    if (t == Type.SCOPE) {
                        sb.append("scope");
                    } else if (t == Type.THIS) {
                        sb.append("this");
                    } else if (t.isObject()) {
                        String desc = t.getDescriptor();
                        final int slash = desc.lastIndexOf('/');
                        if (slash != -1) {
                            desc = desc.substring(slash + 1, desc.length() - 1);
                        }
                        if ("Object".equals(desc)) {
                            sb.append('O');
                        } else {
                            sb.append(desc);
                        }
                    } else {
                        sb.append(t.getDescriptor());
                    }

                    if (iter.hasNext()) {
                        sb.append(' ');
                    }
                }
                sb.append('}');
                sb.append(' ');
            }

            pad = padConstant - sb.length();
            while (pad > 0) {
                sb.append(' ');
                pad--;
            }

            for (final Object arg : args) {
                sb.append(arg);
                sb.append(' ');
            }

            if (context != null) { //early bootstrap code doesn't have inited context yet
                LOG.info(sb.toString());
                if (DEBUG_TRACE_LINE == linePrefix) {
                    new Throwable().printStackTrace(LOG.getOutputStream());
                }
            }

        }
    }


    /**
     * Abstraction for labels, separating a label from the underlying
     * byte code emitter. Also augmenting label with e.g. a name
     * for easier debugging and reading code
     *
     * see -Dnashorn.codegen.debug, --log=codegen
     */
    public static class Label extends jdk.internal.org.objectweb.asm.Label {
        /** Name of this label */
        private final String name;

        /** Type stack at this label */
        private ArrayDeque<Type> stack;

        /**
         * Constructor
         *
         * @param name name of this label
         */
        public Label(final String name) {
            super();
            this.name = name;
        }

        /**
         * Copy constructor
         *
         * @param label a label to clone
         */
        public Label(final Label label) {
            super();
            name = label.name;
        }

        ArrayDeque<Type> getStack() {
            return stack;
        }

        void setStack(final ArrayDeque<Type> stack) {
            this.stack = stack;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            String s = super.toString();
            s = s.substring(1, s.length());
            sb.append(name).append('_').append(Long.toHexString(Long.parseLong(s)));

            return sb.toString();
        }
    }

    /**
     * Condition enum used for all kinds of jumps, regardless of type
     */
    static enum Condition {
        EQ,
        NE,
        LE,
        LT,
        GE,
        GT;

        public static Condition forRuntimeRequest(final RuntimeNode.Request request) {
            try {
                final String reqString = request.toString().replace("_STRICT", "");
                return Condition.valueOf(reqString);
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }

        public static int toUnary(final Condition c) {
            switch (c) {
            case EQ:
                return IFEQ;
            case NE:
                return IFNE;
            case LE:
                return IFLE;
            case LT:
                return IFLT;
            case GE:
                return IFGE;
            case GT:
                return IFGT;
            default:
                assert false;
                return -1;
            }
        }

        public static int toBinary(final Condition c) {
            return toBinary(c, false);
        }

        public static int toBinary(final Condition c, final boolean isObject) {
            switch (c) {
            case EQ:
                return isObject ? IF_ACMPEQ : IF_ICMPEQ;
            case NE:
                return isObject ? IF_ACMPNE : IF_ICMPNE;
            case LE:
                return IF_ICMPLE;
            case LT:
                return IF_ICMPLT;
            case GE:
                return IF_ICMPGE;
            case GT:
                return IF_ICMPGT;
            default:
                assert false;
                return -1;
            }
        }
    }

    /**
     * Set the current function node being emitted
     * @param functionNode the function node
     */
    public void setFunctionNode(final FunctionNode functionNode) {
        this.functionNode = functionNode;
    }

    /**
     * Get the split node for this method emitter, if this is code
     * generation due to splitting large methods
     *
     * @return split node
     */
    public SplitNode getSplitNode() {
        return splitNode;
    }

    /**
     * Set the split node for this method emitter
     * @param splitNode split node
     */
    public void setSplitNode(final SplitNode splitNode) {
        this.splitNode = splitNode;
    }
}
