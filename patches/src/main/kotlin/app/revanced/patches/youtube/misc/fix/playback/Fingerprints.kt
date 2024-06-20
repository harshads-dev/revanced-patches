package app.revanced.patches.youtube.misc.fix.playback

import app.revanced.patcher.fingerprint
import app.revanced.util.containsWideLiteralInstructionValue
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstruction
import app.revanced.util.literal
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val buildInitPlaybackRequestFingerprint = fingerprint {
    returns("Lorg/chromium/net/UrlRequest\$Builder;")
    opcodes(
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.IGET_OBJECT, // Moves the request URI string to a register to build the request with.
    )
    strings(
        "Content-Type",
        "Range",
    )
}

internal val buildPlayerRequestURIFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    opcodes(
        Opcode.INVOKE_VIRTUAL, // Register holds player request URI.
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.IPUT_OBJECT,
        Opcode.IGET_OBJECT,
        Opcode.MONITOR_EXIT,
        Opcode.RETURN_OBJECT,
    )
    strings(
        "youtubei/v1",
        "key",
        "asig",
    )
}

internal val createPlaybackSpeedMenuItemFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("V")
    opcodes(
        Opcode.IGET_OBJECT, // First instruction of the method
        Opcode.IGET_OBJECT,
        Opcode.IGET_OBJECT,
        Opcode.CONST_4,
        Opcode.IF_EQZ,
        Opcode.INVOKE_INTERFACE,
        null, // MOVE_RESULT or MOVE_RESULT_OBJECT, Return value controls the creation of the playback speed menu item.
    )
    // 19.01 and earlier is missing the second parameter.
    // Since this fingerprint is somewhat weak, work around by checking for both method parameter signatures.
    custom { method, _ ->
        // 19.01 and earlier parameters are: "[L"
        // 19.02+ parameters are "[L", "F"
        val parameterTypes = method.parameterTypes
        val firstParameter = parameterTypes.firstOrNull()

        if (firstParameter == null || !firstParameter.startsWith("[L")) {
            return@custom false
        }

        parameterTypes.size == 1 || (parameterTypes.size == 2 && parameterTypes[1] == "F")
    }
}

internal val createPlayerRequestBodyFingerprint = fingerprint {
    returns("V")
    parameters("L")
    opcodes(
        Opcode.CHECK_CAST,
        Opcode.IGET,
        Opcode.AND_INT_LIT16,
    )
    strings("ms")
}

internal fun indexOfBuildModelInstruction(method: Method) =
    method.indexOfFirstInstruction {
        val reference = getReference<FieldReference>()
        reference?.definingClass == "Landroid/os/Build;" &&
            reference.name == "MODEL" &&
            reference.type == "Ljava/lang/String;"
    }

internal val createPlayerRequestBodyWithModelFingerprint = fingerprint {
    returns("L")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    parameters()
    custom { method, _ ->
        method.containsWideLiteralInstructionValue(1073741824) && indexOfBuildModelInstruction(method) >= 0
    }
}

internal val playerGestureConfigSyntheticFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("V")
    parameters("Ljava/lang/Object;")
    opcodes(
        Opcode.SGET_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.IF_EQZ,
        Opcode.IF_EQZ,
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_INTERFACE,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL, // playerGestureConfig.downAndOutLandscapeAllowed.
        Opcode.MOVE_RESULT,
        Opcode.CHECK_CAST,
        Opcode.IPUT_BOOLEAN,
        Opcode.INVOKE_INTERFACE,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL, // playerGestureConfig.downAndOutPortraitAllowed.
        Opcode.MOVE_RESULT,
        Opcode.IPUT_BOOLEAN,
        Opcode.RETURN_VOID,
    )
    custom { method, classDef ->
        fun indexOfDownAndOutAllowedInstruction() =
            method.indexOfFirstInstruction {
                val reference = getReference<MethodReference>()
                reference?.definingClass == "Lcom/google/android/libraries/youtube/innertube/model/media/PlayerConfigModel;" &&
                    reference.parameterTypes.isEmpty() &&
                    reference.returnType == "Z"
            }

        // This method is always called "a" because this kind of class always has a single method.
        method.name == "a" &&
            classDef.methods.count() == 2 &&
            indexOfDownAndOutAllowedInstruction() >= 0
    }
}

internal val setPlayerRequestClientTypeFingerprint = fingerprint {
    opcodes(
        Opcode.IGET,
        Opcode.IPUT, // Sets ClientInfo.clientId.
    )
    strings("10.29")
    literal { 134217728 }
}