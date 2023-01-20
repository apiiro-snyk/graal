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
package com.oracle.truffle.api.operation.tracing;

import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.operation.tracing.OperationsStatistics.DisabledExecutionTracer;

// per-context per-ops per-thread
public abstract class ExecutionTracer {

    // TODO refactor this to store everything in a class
    // TODO refactor using an annotation instead of a static class.
    // target file name
    static final Map<Class<?>, String> DECISIONS_FILE_MAP = new HashMap<>();
    // operation class ->
    static final Map<Class<?>, String[]> INSTR_NAMES_MAP = new HashMap<>();
    // operation class -> instruction -> specialization
    static final Map<Class<?>, String[][]> SPECIALIZATION_NAMES_MAP = new HashMap<>();

    public static ExecutionTracer get(Class<?> operationsClass) {
        OperationsStatistics stats = OperationsStatistics.STATISTICS.get();
        if (stats == null) {
            return DisabledExecutionTracer.INSTANCE;
        } else {
            return stats.getStatsistics(operationsClass).getTracer();
        }
    }

    public static synchronized void initialize(Class<?> opsClass, String decisionsFile, String[] instrNames, String[][] specNames) {
        DECISIONS_FILE_MAP.put(opsClass, decisionsFile);
        INSTR_NAMES_MAP.put(opsClass, instrNames);
        SPECIALIZATION_NAMES_MAP.put(opsClass, specNames);
    }

    // TODO rename startRoot
    public abstract void startFunction(Node node);

    public abstract void endFunction(Node node);

    public abstract void traceInstruction(int bci, int id, int... arguments);

    // TODO needs implementation. probably using introspection.
    public abstract void traceActiveSpecializations(int bci, int id, boolean[] activeSpecializations);

    public abstract void traceSpecialization(int bci, int id, int specializationId, Object... values);

    public abstract void traceStartBasicBlock(int bci);
}