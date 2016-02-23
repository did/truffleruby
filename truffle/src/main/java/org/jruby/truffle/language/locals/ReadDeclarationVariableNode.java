/*
 * Copyright (c) 2013, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.language.locals;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.SourceSection;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.truffle.RubyContext;
import org.jruby.truffle.language.RubyNode;
import org.jruby.truffle.language.arguments.RubyArguments;
import org.jruby.truffle.language.translator.ReadLocalNode;
import org.jruby.truffle.language.translator.Translator;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ReadDeclarationVariableNode extends ReadLocalNode {

    private final LocalVariableType type;
    private final int frameDepth;
    private final FrameSlot frameSlot;

    @Child private ReadFrameSlotNode readFrameSlotNode;

    public ReadDeclarationVariableNode(RubyContext context, SourceSection sourceSection, LocalVariableType type,
                                       int frameDepth, FrameSlot frameSlot) {
        super(context, sourceSection);
        this.type = type;
        this.frameDepth = frameDepth;
        this.frameSlot = frameSlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        checkReadFrameSlotNode();
        final MaterializedFrame declarationFrame = RubyArguments.getDeclarationFrame(frame, frameDepth);
        return readFrameSlotNode.executeRead(declarationFrame);
    }


    @Override
    public Object isDefined(VirtualFrame frame) {
        switch (type) {
            case FRAME_LOCAL:
                return coreStrings().LOCAL_VARIABLE.createInstance();

            case FRAME_LOCAL_GLOBAL:
                checkReadFrameSlotNode();

                if (readFrameSlotNode.executeRead(frame) != nil()) {
                    return coreStrings().GLOBAL_VARIABLE.createInstance();
                } else {
                    return nil();
                }

            case ALWAYS_DEFINED_GLOBAL:
                return coreStrings().GLOBAL_VARIABLE.createInstance();

            default:
                throw new UnsupportedOperationException("didn't expect local type " + type);
        }
    }

    private void checkReadFrameSlotNode() {
        if (readFrameSlotNode == null) {
            CompilerDirectives.transferToInterpreter();
            readFrameSlotNode = insert(ReadFrameSlotNodeGen.create(frameSlot));
        }
    }

    @Override
    public RubyNode makeWriteNode(RubyNode rhs) {
        return new WriteDeclarationVariableNode(getContext(), getSourceSection(), rhs, frameDepth, frameSlot);
    }

}
