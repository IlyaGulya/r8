// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.benchmarks.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

public final class AllocatorTimingAgent {

  private static final String TARGET_CLASS =
      "com/android/tools/r8/ir/regalloc/LinearScanRegisterAllocator";
  private static final String RECORDER = Type.getInternalName(AllocatorTimingRecorder.class);

  public static void premain(String arguments, Instrumentation instrumentation) {
    AllocatorTimingRecorder.configure(arguments);
    instrumentation.addTransformer(new AllocatorTransformer());
  }

  private static class AllocatorTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer)
        throws IllegalClassFormatException {
      if (!TARGET_CLASS.equals(className)) {
        return null;
      }
      ClassReader reader = new ClassReader(classfileBuffer);
      ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
      reader.accept(new AllocatorClassVisitor(writer), ClassReader.EXPAND_FRAMES);
      return writer.toByteArray();
    }
  }

  private static class AllocatorClassVisitor extends ClassVisitor {

    AllocatorClassVisitor(ClassVisitor visitor) {
      super(Opcodes.ASM9, visitor);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
      if (!name.equals("allocateRegisters") || !descriptor.equals("()V")) {
        return visitor;
      }
      return new AdviceAdapter(Opcodes.ASM9, visitor, access, name, descriptor) {
        private int holder;
        private int wallStart;
        private int cpuStart;

        @Override
        protected void onMethodEnter() {
          loadThis();
          visitFieldInsn(
              Opcodes.GETFIELD, TARGET_CLASS, "code", "Lcom/android/tools/r8/ir/code/IRCode;");
          visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              "com/android/tools/r8/ir/code/IRCode",
              "context",
              "()Lcom/android/tools/r8/graph/ProgramMethod;",
              false);
          visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              "com/android/tools/r8/graph/ProgramMethod",
              "getHolderType",
              "()Lcom/android/tools/r8/graph/DexType;",
              false);
          visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              "com/android/tools/r8/graph/DexType",
              "toDescriptorString",
              "()Ljava/lang/String;",
              false);
          holder = newLocal(Type.getType(String.class));
          storeLocal(holder);
          invokeStatic(
              Type.getType(System.class), new org.objectweb.asm.commons.Method("nanoTime", "()J"));
          wallStart = newLocal(Type.LONG_TYPE);
          storeLocal(wallStart);
          invokeStatic(
              Type.getType(AllocatorTimingRecorder.class),
              new org.objectweb.asm.commons.Method("currentThreadCpuTime", "()J"));
          cpuStart = newLocal(Type.LONG_TYPE);
          storeLocal(cpuStart);
        }

        @Override
        protected void onMethodExit(int opcode) {
          loadLocal(holder);
          loadLocal(wallStart);
          loadLocal(cpuStart);
          visitMethodInsn(
              Opcodes.INVOKESTATIC, RECORDER, "record", "(Ljava/lang/String;JJ)V", false);
        }
      };
    }
  }

  private AllocatorTimingAgent() {}
}
