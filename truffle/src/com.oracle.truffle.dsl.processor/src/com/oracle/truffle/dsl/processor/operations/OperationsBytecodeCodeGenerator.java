/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.dsl.processor.operations;

import static com.oracle.truffle.dsl.processor.operations.OperationGeneratorUtils.combineBoxingBits;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

import com.oracle.truffle.dsl.processor.ProcessorContext;
import com.oracle.truffle.dsl.processor.TruffleTypes;
import com.oracle.truffle.dsl.processor.generator.FlatNodeGenFactory.GeneratorMode;
import com.oracle.truffle.dsl.processor.generator.GeneratorUtils;
import com.oracle.truffle.dsl.processor.generator.NodeCodeGenerator;
import com.oracle.truffle.dsl.processor.generator.StaticConstants;
import com.oracle.truffle.dsl.processor.java.ElementUtils;
import com.oracle.truffle.dsl.processor.java.model.CodeAnnotationMirror;
import com.oracle.truffle.dsl.processor.java.model.CodeAnnotationValue;
import com.oracle.truffle.dsl.processor.java.model.CodeExecutableElement;
import com.oracle.truffle.dsl.processor.java.model.CodeTreeBuilder;
import com.oracle.truffle.dsl.processor.java.model.CodeTypeElement;
import com.oracle.truffle.dsl.processor.java.model.CodeTypeMirror.ArrayCodeTypeMirror;
import com.oracle.truffle.dsl.processor.java.model.CodeVariableElement;
import com.oracle.truffle.dsl.processor.operations.instructions.CustomInstruction;
import com.oracle.truffle.dsl.processor.operations.instructions.FrameKind;
import com.oracle.truffle.dsl.processor.operations.instructions.Instruction;
import com.oracle.truffle.dsl.processor.operations.instructions.Instruction.ExecutionVariables;
import com.oracle.truffle.dsl.processor.operations.instructions.QuickenedInstruction;

public class OperationsBytecodeCodeGenerator {

    private static final Set<Modifier> MOD_PRIVATE_FINAL = Set.of(Modifier.PRIVATE, Modifier.FINAL);
    private static final Set<Modifier> MOD_PRIVATE_STATIC = Set.of(Modifier.PRIVATE, Modifier.STATIC);
    private static final Set<Modifier> MOD_PRIVATE_STATIC_FINAL = Set.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);

    static final Object MARKER_CHILD = new Object();
    static final Object MARKER_CONST = new Object();

    static final boolean DO_STACK_LOGGING = false;

    final ProcessorContext context = ProcessorContext.getInstance();
    final TruffleTypes types = context.getTypes();

    private static final String ConditionProfile_Name = "com.oracle.truffle.api.profiles.ConditionProfile";
    final DeclaredType typeConditionProfile = context.getDeclaredType(ConditionProfile_Name);

    private final CodeTypeElement typEnclosingElement;
    private final OperationsData m;
    private final boolean withInstrumentation;
    private final boolean isUncached;
    private final CodeTypeElement baseClass;
    private final CodeTypeElement opNodeImpl;
    private final CodeTypeElement typExceptionHandler;

    public OperationsBytecodeCodeGenerator(CodeTypeElement typEnclosingElement, CodeTypeElement baseClass, CodeTypeElement opNodeImpl, CodeTypeElement typExceptionHandler, OperationsData m,
                    boolean withInstrumentation, boolean isUncached) {
        this.typEnclosingElement = typEnclosingElement;
        this.baseClass = baseClass;
        this.opNodeImpl = opNodeImpl;
        this.typExceptionHandler = typExceptionHandler;
        this.m = m;
        this.withInstrumentation = withInstrumentation;
        this.isUncached = isUncached;
    }

    /**
     * Create the BytecodeNode type. This type contains the bytecode interpreter, and is the
     * executable Truffle node.
     */
    public CodeTypeElement createBuilderBytecodeNode() {
        String namePrefix = withInstrumentation ? "Instrumentable" : isUncached ? "Uncached" : "";

        CodeTypeElement builderBytecodeNodeType = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_FINAL, namePrefix + "BytecodeNode", baseClass.asType());

        initializeInstructions(builderBytecodeNodeType);

        builderBytecodeNodeType.add(createBytecodeLoop(baseClass));

        if (m.isGenerateAOT()) {
            builderBytecodeNodeType.add(createPrepareAot(baseClass));

            builderBytecodeNodeType.add(new CodeExecutableElement(MOD_PRIVATE_STATIC, context.getType(boolean.class), "isAdoptable")).getBuilder().statement("return true");
        }

        builderBytecodeNodeType.add(createDumpCode(baseClass));

        return builderBytecodeNodeType;
    }

    private CodeExecutableElement createPrepareAot(CodeTypeElement baseType) {
        CodeExecutableElement mPrepareForAot = GeneratorUtils.overrideImplement(baseType, "prepareForAOT");

        CodeTreeBuilder b = mPrepareForAot.createBuilder();

        ExecutionVariables vars = new ExecutionVariables();
        populateVariables(vars, m);

        b.declaration("int", vars.bci.getName(), "0");

        b.startWhile().variable(vars.bci).string(" < ").variable(vars.bc).string(".length").end().startBlock();

        b.tree(OperationGeneratorUtils.createInstructionSwitch(m, vars, withInstrumentation, instr -> {
            if (instr == null) {
                return null;
            }

            CodeTreeBuilder binstr = b.create();

            binstr.tree(instr.createPrepareAOT(vars, CodeTreeBuilder.singleString("language"), CodeTreeBuilder.singleString("root")));
            binstr.startAssign(vars.bci).variable(vars.bci).string(" + ").tree(instr.createLength()).end();
            binstr.statement("break");

            return binstr.build();
        }));

        b.end();

        return mPrepareForAot;
    }

    private CodeExecutableElement createDumpCode(CodeTypeElement baseType) {
        CodeExecutableElement mDump = GeneratorUtils.overrideImplement(baseType, "getIntrospectionData");

        CodeTreeBuilder b = mDump.getBuilder();
        ExecutionVariables vars = new ExecutionVariables();
        populateVariables(vars, m);

        CodeVariableElement varHandlers = new CodeVariableElement(new ArrayCodeTypeMirror(typExceptionHandler.asType()), "$handlers");

        b.declaration("int", vars.bci.getName(), "0");
        b.declaration("ArrayList<Object[]>", "target", "new ArrayList<>()");

        b.startWhile().string("$bci < $bc.length").end().startBlock(); // while {

        b.tree(OperationGeneratorUtils.createInstructionSwitch(m, vars, withInstrumentation, op -> {
            CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();

            if (op == null) {
                builder.declaration("Object[]", "dec", "new Object[]{$bci, \"unknown\", Arrays.copyOfRange($bc, $bci, $bci + 1), null}");
                builder.statement("$bci++");
            } else {
                builder.tree(op.createDumpCode(vars));
                builder.startAssign(vars.bci).variable(vars.bci).string(" + ").tree(op.createLength()).end();
            }

            builder.statement("target.add(dec)");

            builder.statement("break");
            return builder.build();
        }));

        b.end(); // }

        b.declaration("ArrayList<Object[]>", "ehTarget", "new ArrayList<>()");

        b.startFor().string("int i = 0; i < ").variable(varHandlers).string(".length; i++").end();
        b.startBlock();

        b.startStatement().startCall("ehTarget.add").startNewArray((ArrayType) context.getType(Object[].class), null);
        b.startGroup().variable(varHandlers).string("[i].startBci").end();
        b.startGroup().variable(varHandlers).string("[i].endBci").end();
        b.startGroup().variable(varHandlers).string("[i].handlerBci").end();
        b.end(3);

        b.end();

        // b.startIf().string("sourceInfo != null").end();
        // b.startBlock();
        // {
        // b.statement("sb.append(\"Source info:\\n\")");
        // b.startFor().string("int i = 0; i < sourceInfo[0].length; i++").end();
        // b.startBlock();
        //
        // b.statement("sb.append(String.format(\" bci=%04x, offset=%d, length=%d\\n\",
        // sourceInfo[0][i],
        // sourceInfo[1][i], sourceInfo[2][i]))");
        //
        // b.end();
        // }
        // b.end();

        b.startReturn().string("OperationIntrospection.Provider.create(new Object[]{0, target.toArray(), ehTarget.toArray()})").end();

        vars.bci = null;

        return mDump;
    }

    static void populateVariables(ExecutionVariables vars, OperationsData m) {
        ProcessorContext context = ProcessorContext.getInstance();
        TruffleTypes types = context.getTypes();

        vars.bc = new CodeVariableElement(context.getType(short[].class), "$bc");
        vars.sp = new CodeVariableElement(context.getType(int.class), "$sp");
        vars.bci = new CodeVariableElement(context.getType(int.class), "$bci");
        vars.stackFrame = new CodeVariableElement(types.Frame, m.enableYield ? "$stackFrame" : "$frame");
        vars.localFrame = new CodeVariableElement(types.Frame, m.enableYield ? "$localFrame" : "$frame");
        vars.consts = new CodeVariableElement(context.getType(Object[].class), "$consts");
        vars.children = new CodeVariableElement(new ArrayCodeTypeMirror(types.Node), "$children");
    }

    private CodeExecutableElement createBytecodeLoop(CodeTypeElement baseType) {
        CodeExecutableElement mContinueAt = GeneratorUtils.overrideImplement(baseType, "continueAt");
        createExplodeLoop(mContinueAt);

        var ctx = m.getOperationsContext();

        ExecutionVariables vars = new ExecutionVariables();
        populateVariables(vars, m);

        mContinueAt.addAnnotationMirror(new CodeAnnotationMirror(context.getDeclaredType("com.oracle.truffle.api.HostCompilerDirectives.BytecodeInterpreterSwitch")));

        CodeTreeBuilder b = mContinueAt.getBuilder();

        CodeVariableElement varCurOpcode = new CodeVariableElement(context.getType(short.class), "curOpcode");
        CodeVariableElement varHandlers = new CodeVariableElement(new ArrayCodeTypeMirror(typExceptionHandler.asType()), "$handlers");

        b.declaration("int", vars.sp.getName(), CodeTreeBuilder.singleString("$startSp"));
        b.declaration("int", vars.bci.getName(), CodeTreeBuilder.singleString("$startBci"));
        b.declaration("Counter", "loopCounter", "new Counter()");

        // this moves the frame null check out of the loop
        b.startStatement().startCall(vars.stackFrame, "getArguments").end(2);

        if (isUncached) {
            // todo: better signaling to compiler that the method is not ready for compilation
            b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());
            b.declaration("int", "uncachedExecuteCount", "$this.uncachedExecuteCount");
        }

        CodeVariableElement varTracer;

        if (m.isTracing()) {
            varTracer = new CodeVariableElement(types.ExecutionTracer, "tracer");
            b.startAssign("ExecutionTracer " + varTracer.getName()).startStaticCall(types.ExecutionTracer, "get");
            b.typeLiteral(m.getTemplateType().asType());
            b.end(2);

            b.startStatement().startCall(varTracer, "startFunction").string("$this").end(2);
        } else {
            varTracer = null;
        }

        b.string("loop: ");
        b.startWhile().string("true").end();
        b.startBlock();

        vars.tracer = varTracer;

        b.tree(GeneratorUtils.createPartialEvaluationConstant(vars.bci));
        b.tree(GeneratorUtils.createPartialEvaluationConstant(vars.sp));

        b.declaration("int", varCurOpcode.getName(), CodeTreeBuilder.createBuilder().tree(OperationGeneratorUtils.createReadOpcode(vars.bc, vars.bci)).string(" & 0xffff").build());

        b.tree(GeneratorUtils.createPartialEvaluationConstant(varCurOpcode));

        if (varTracer != null) {
            b.startIf().string("$this.isBbStart[$bci]").end().startBlock();
            b.startStatement().startCall(varTracer, "traceStartBasicBlock");
            b.variable(vars.bci);
            b.end(2);
            b.end();
        }

        b.startTryBlock();

        b.startAssert().variable(vars.sp).string(" >= maxLocals : \"stack underflow @ \" + $bci").end();

        b.startSwitch().string("curOpcode").end();
        b.startBlock();

        for (Instruction op : m.getInstructions()) {
            if (op.isInstrumentationOnly() && !withInstrumentation) {
                continue;
            }

            if (op.neverInUncached() && isUncached) {
                continue;
            }

            for (String line : op.dumpInfo().split("\n")) {
                b.lineComment(line);
            }

            Runnable createBody = () -> {
                if (m.isTracing()) {
                    b.startStatement().startCall(varTracer, "traceInstruction");
                    b.variable(vars.bci);
                    b.variable(op.opcodeIdField);
                    b.end(2);
                }

                if (isUncached) {
                    b.tree(op.createExecuteUncachedCode(vars));
                } else {
                    b.tree(op.createExecuteCode(vars));
                }

                if (!op.isBranchInstruction()) {
                    b.startAssign(vars.bci).variable(vars.bci).string(" + ").tree(op.createLength()).end();
                    b.statement("continue loop");
                }
            };

            if (ctx.hasBoxingElimination() && !isUncached) {
                if (op.splitOnBoxingElimination()) {
                    for (FrameKind kind : op.getBoxingEliminationSplits()) {
                        b.startCase().tree(combineBoxingBits(ctx, op, kind)).end();
                        b.startBlock();
                        vars.specializedKind = kind;
                        createBody.run();
                        vars.specializedKind = null;
                        b.end();
                    }
                    if (op.hasGeneric()) {
                        b.startCase().tree(combineBoxingBits(ctx, op, 7)).end();
                        b.startBlock();
                        createBody.run();
                        b.end();
                    }
                } else if (op.numPushedValues == 0 || op.alwaysBoxed()) {
                    b.startCase().tree(combineBoxingBits(ctx, op, 0)).end();
                    b.startBlock();
                    createBody.run();
                    b.end();
                } else {
                    for (FrameKind kind : op.getBoxingEliminationSplits()) {
                        b.startCase().tree(combineBoxingBits(ctx, op, kind)).end();
                    }

                    b.startBlock();
                    b.declaration("short", "primitiveTag", "(short) ((curOpcode >> 13) & 0x0007)");
                    createBody.run();
                    b.end();
                }
            } else {
                b.startCase().tree(combineBoxingBits(ctx, op, 0)).end();
                b.startBlock();
                if (ctx.hasBoxingElimination()) {
                    vars.specializedKind = FrameKind.OBJECT;
                }
                createBody.run();
                vars.specializedKind = null;
                b.end();
            }

            vars.inputs = null;
            vars.results = null;
        }

        b.caseDefault().startCaseBlock();
        b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());
        b.tree(GeneratorUtils.createShouldNotReachHere("unknown opcode encountered: \" + curOpcode + \" @ \" + $bci + \""));
        b.end();

        b.end(); // switch block

        b.end().startCatchBlock(context.getDeclaredType("com.oracle.truffle.api.exception.AbstractTruffleException"), "ex");

        b.tree(GeneratorUtils.createPartialEvaluationConstant(vars.bci));

        // if (m.isTracing()) {
        // b.startStatement().startCall(fldTracer, "traceException");
        // b.string("ex");
        // b.end(2);
        // }

        b.startFor().string("int handlerIndex = " + varHandlers.getName() + ".length - 1; handlerIndex >= 0; handlerIndex--").end();
        b.startBlock();

        b.tree(GeneratorUtils.createPartialEvaluationConstant("handlerIndex"));

        b.declaration(typExceptionHandler.asType(), "handler", varHandlers.getName() + "[handlerIndex]");

        b.startIf().string("handler.startBci > $bci || handler.endBci <= $bci").end();
        b.statement("continue");

        b.startAssign(vars.sp).string("handler.startStack + maxLocals").end();
        // todo: check exception type (?)

        b.startStatement().startCall(vars.stackFrame, "setObject").string("handler.exceptionIndex").string("ex").end(2);

        b.statement("$bci = handler.handlerBci");
        b.statement("continue loop");

        b.end(); // for (handlerIndex ...)

        b.startThrow().string("ex").end();

        b.end(); // catch block

        b.end(); // while loop

        return mContinueAt;
    }

    private void createExplodeLoop(CodeExecutableElement mContinueAt) {
        CodeAnnotationMirror annExplodeLoop = new CodeAnnotationMirror(types.ExplodeLoop);
        mContinueAt.addAnnotationMirror(annExplodeLoop);
        annExplodeLoop.setElementValue("kind", new CodeAnnotationValue(new CodeVariableElement(
                        context.getDeclaredType("com.oracle.truffle.api.nodes.ExplodeLoop.LoopExplosionKind"), "MERGE_EXPLODE")));
    }

    private void initializeInstructions(CodeTypeElement builderBytecodeNodeType) throws AssertionError {
        StaticConstants staticConstants = new StaticConstants(true);
        for (Instruction instr : m.getInstructions()) {
            if (!(instr instanceof CustomInstruction)) {
                continue;
            }

            CustomInstruction cinstr = (CustomInstruction) instr;

            final Set<String> methodNames = new HashSet<>();
            final Set<String> innerTypeNames = new HashSet<>();

            final SingleOperationData soData = cinstr.getData();

            OperationsBytecodeNodeGeneratorPlugs plugs = new OperationsBytecodeNodeGeneratorPlugs(m, innerTypeNames, methodNames, cinstr, staticConstants, isUncached);
            cinstr.setPlugs(plugs);

            NodeCodeGenerator generator = new NodeCodeGenerator();
            generator.setPlugs(plugs);
            generator.setGeneratorMode(GeneratorMode.OPERATIONS);

            List<CodeTypeElement> resultList = generator.create(context, null, soData.getNodeData());
            if (resultList.size() != 1) {
                throw new AssertionError("Node generator did not return exactly one class");
            }
            plugs.finishUp();
            CodeTypeElement result = resultList.get(0);

            List<String> executeNames = m.getOperationsContext().getBoxingKinds().stream().map(
                            x -> plugs.transformNodeMethodName(x == FrameKind.OBJECT ? "execute" : "execute" + x.getFrameName())).collect(Collectors.toList());
            CodeExecutableElement[] executeMethods = new CodeExecutableElement[isUncached ? 1 : executeNames.size()];
            List<CodeExecutableElement> execs = new ArrayList<>();

            TypeElement target = result;

            if (isUncached) {
                target = (TypeElement) result.getEnclosedElements().stream().filter(x -> x.getSimpleName().toString().equals("Uncached")).findFirst().get();
            }

            Set<String> copiedMethod = new HashSet<>();

            for (ExecutableElement ex : ElementFilter.methodsIn(target.getEnclosedElements())) {
                if (!methodNames.contains(ex.getSimpleName().toString())) {
                    continue;
                }

                if (copiedMethod.contains(ex.getSimpleName().toString())) {
                    continue;
                }

                String simpleName = ex.getSimpleName().toString();

                if (isUncached) {
                    if (simpleName.equals(plugs.transformNodeMethodName("executeUncached"))) {
                        executeMethods[0] = (CodeExecutableElement) ex;
                    } else if (executeNames.contains(simpleName)) {
                        continue;
                    }
                } else {
                    if (simpleName.equals(plugs.transformNodeMethodName("executeUncached"))) {
                        continue;
                    } else if (executeNames.contains(simpleName)) {
                        executeMethods[executeNames.indexOf(simpleName)] = (CodeExecutableElement) ex;
                    }
                }

                execs.add((CodeExecutableElement) ex);
                copiedMethod.add(ex.getSimpleName().toString());
            }

            for (CodeExecutableElement el : executeMethods) {
                if (el != null) {
                    el.getAnnotationMirrors().removeIf(x -> ElementUtils.typeEquals(x.getAnnotationType(), types.CompilerDirectives_TruffleBoundary));
                }
            }

            for (TypeElement te : ElementFilter.typesIn(result.getEnclosedElements())) {
                if (!innerTypeNames.contains(te.getSimpleName().toString())) {
                    continue;
                }

                builderBytecodeNodeType.add(te);
            }

            CodeExecutableElement metPrepareForAOT = null;

            for (CodeExecutableElement exToCopy : execs) {
                boolean isBoundary = exToCopy.getAnnotationMirrors().stream().anyMatch(x -> x.getAnnotationType().equals(types.CompilerDirectives_TruffleBoundary));

                String exName = exToCopy.getSimpleName().toString();
                boolean isExecute = executeNames.contains(exName) || exName.contains("_executeUncached_");
                boolean isExecuteAndSpecialize = exName.endsWith("_executeAndSpecialize_");
                boolean isFallbackGuard = exName.endsWith("_fallbackGuard__");

                if (instr instanceof QuickenedInstruction) {
                    if (isExecuteAndSpecialize) {
                        continue;
                    }
                }

                if (isExecute || isExecuteAndSpecialize || isFallbackGuard) {
                    List<VariableElement> params = exToCopy.getParameters();

                    int i = 0;
                    for (; i < params.size(); i++) {
                        TypeMirror paramType = params.get(i).asType();
                        if (ElementUtils.typeEquals(paramType, types.Frame) || ElementUtils.typeEquals(paramType, types.VirtualFrame)) {
                            params.remove(i);
                            i--;
                        }
                    }
                }

                if (cinstr.isVariadic()) {
                    exToCopy.getParameters().add(0, new CodeVariableElement(context.getType(int.class), "$numVariadics"));
                }
                exToCopy.getParameters().add(0, new CodeVariableElement(arrayOf(types.Node), "$children"));
                exToCopy.getParameters().add(0, new CodeVariableElement(context.getType(Object[].class), "$consts"));
                exToCopy.getParameters().add(0, new CodeVariableElement(context.getType(int.class), "$sp"));
                exToCopy.getParameters().add(0, new CodeVariableElement(context.getType(int.class), "$bci"));
                exToCopy.getParameters().add(0, new CodeVariableElement(context.getType(short[].class), "$bc"));
                exToCopy.getParameters().add(0, new CodeVariableElement(opNodeImpl.asType(), "$this"));
                if (!isBoundary) {
                    if (m.enableYield) {
                        exToCopy.getParameters().add(0, new CodeVariableElement(types.VirtualFrame, "$localFrame"));
                        exToCopy.getParameters().add(0, new CodeVariableElement(types.VirtualFrame, "$stackFrame"));
                    } else {
                        exToCopy.getParameters().add(0, new CodeVariableElement(types.VirtualFrame, "$frame"));
                    }
                }
                exToCopy.getModifiers().remove(Modifier.PUBLIC);
                exToCopy.getModifiers().add(Modifier.PRIVATE);
                exToCopy.getModifiers().add(Modifier.STATIC);
                exToCopy.getAnnotationMirrors().removeIf(x -> x.getAnnotationType().equals(context.getType(Override.class)));
                builderBytecodeNodeType.add(exToCopy);

                if (exName.equals(plugs.transformNodeMethodName("prepareForAOT"))) {
                    metPrepareForAOT = exToCopy;
                }
            }

            if (isUncached) {
                cinstr.setUncachedExecuteMethod(executeMethods[0]);
            } else {
                cinstr.setExecuteMethod(executeMethods);
            }
            cinstr.setPrepareAOTMethod(metPrepareForAOT);

            if (m.isTracing()) {
                OperationGeneratorUtils.createHelperMethod(typEnclosingElement, "doGetStateBits_" + cinstr.getUniqueName() + "_", () -> {
                    CodeExecutableElement metGetSpecBits = new CodeExecutableElement(Set.of(Modifier.PRIVATE, Modifier.STATIC), arrayOf(context.getType(boolean.class)),
                                    "doGetStateBits_" + cinstr.getUniqueName() + "_");

                    metGetSpecBits.addParameter(new CodeVariableElement(arrayOf(context.getType(short.class)), "$bc"));
                    metGetSpecBits.addParameter(new CodeVariableElement(context.getType(int.class), "$bci"));
                    CodeTreeBuilder b = metGetSpecBits.createBuilder();
                    b.tree(plugs.createGetSpecializationBits());

                    cinstr.setGetSpecializationBits(metGetSpecBits);

                    return metGetSpecBits;
                });
            }
        }

        for (CodeVariableElement element : staticConstants.elements()) {
            builderBytecodeNodeType.add(element);
        }
    }

    private static TypeMirror arrayOf(TypeMirror el) {
        return new ArrayCodeTypeMirror(el);
    }
}