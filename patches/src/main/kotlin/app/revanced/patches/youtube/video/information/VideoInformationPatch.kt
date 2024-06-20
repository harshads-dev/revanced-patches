package app.revanced.patches.youtube.video.information

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.revanced.patches.youtube.misc.extensions.sharedExtensionPatch
import app.revanced.patches.youtube.video.playerresponse.Hook
import app.revanced.patches.youtube.video.playerresponse.addPlayerResponseMethodHook
import app.revanced.patches.youtube.video.playerresponse.playerResponseMethodHookPatch
import app.revanced.patches.youtube.video.videoid.hookBackgroundPlayVideoId
import app.revanced.patches.youtube.video.videoid.hookPlayerResponseVideoId
import app.revanced.patches.youtube.video.videoid.hookVideoId
import app.revanced.patches.youtube.video.videoid.videoIdPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/youtube/patches/VideoInformation;"

private lateinit var playerInitMethod: MutableMethod
private var playerInitInsertIndex = 4

private lateinit var timeMethod: MutableMethod
private var timeInitInsertIndex = 2

private lateinit var speedSelectionInsertMethod: MutableMethod
private var speedSelectionInsertIndex = 0
private var speedSelectionValueRegister = 0

// Used by other patches.
lateinit var setPlaybackSpeedContainerClassFieldReference: String
    private set
lateinit var setPlaybackSpeedClassFieldReference: String
    private set
lateinit var setPlaybackSpeedMethodReference: String
    private set

val videoInformationPatch = bytecodePatch(
    description = "Hooks YouTube to get information about the current playing video.",
) {
    dependsOn(
        sharedExtensionPatch,
        videoIdPatch,
        playerResponseMethodHookPatch,
    )

    val playerInitMatch by playerInitFingerprint()
    val createVideoPlayerSeekbarMatch by createVideoPlayerSeekbarFingerprint()
    val playerControllerSetTimeReferenceMatch by playerControllerSetTimeReferenceFingerprint()
    val onPlaybackSpeedItemClickMatch by onPlaybackSpeedItemClickFingerprint()

    execute { context ->
        playerInitMethod = playerInitMatch.mutableClass.methods.first { MethodUtil.isConstructor(it) }

        // hook the player controller for use through the extension
        playerControllerOnCreateHook(EXTENSION_CLASS_DESCRIPTOR, "initialize")

        // seek method
        val seekMatchMethod = seekFingerprint.apply {
            match(context, playerInitMatch.classDef)
        }.match!!.method

        // create helper method
        val seekHelperMethod = ImmutableMethod(
            seekMatchMethod.definingClass,
            "seekTo",
            listOf(ImmutableMethodParameter("J", null, "time")),
            "Z",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(4),
        ).toMutable()

        // get enum type for the seek helper method
        val seekSourceEnumType = seekMatchMethod.parameterTypes[1].toString()

        // insert helper method instructions
        seekHelperMethod.addInstructions(
            0,
            """
                    sget-object v0, $seekSourceEnumType->a:$seekSourceEnumType
                    invoke-virtual {p0, p1, p2, v0}, ${seekMatchMethod.definingClass}->${seekMatchMethod.name}(J$seekSourceEnumType)Z
                    move-result p1
                    return p1
                """,
        )

        // add the seekTo method to the class for the extension to call
        playerInitMatch.mutableClass.methods.add(seekHelperMethod)

        with(createVideoPlayerSeekbarMatch) {
            val videoLengthMethodMatch = videoLengthFingerprint.apply { match(context, classDef) }.match!!

            with(videoLengthMethodMatch.mutableMethod) {
                val videoLengthRegisterIndex = videoLengthMethodMatch.patternMatch!!.endIndex - 2
                val videoLengthRegister = getInstruction<OneRegisterInstruction>(videoLengthRegisterIndex).registerA
                val dummyRegisterForLong = videoLengthRegister + 1 // required for long values since they are wide

                addInstruction(
                    videoLengthMethodMatch.patternMatch!!.endIndex,
                    "invoke-static {v$videoLengthRegister, v$dummyRegisterForLong}, " +
                        "$EXTENSION_CLASS_DESCRIPTOR->setVideoLength(J)V",
                )
            }
        }

        /*
         * Inject call for video ids
         */
        val videoIdMethodDescriptor = "$EXTENSION_CLASS_DESCRIPTOR->setVideoId(Ljava/lang/String;)V"
        hookVideoId(videoIdMethodDescriptor)
        hookBackgroundPlayVideoId(videoIdMethodDescriptor)
        hookPlayerResponseVideoId(
            "$EXTENSION_CLASS_DESCRIPTOR->setPlayerResponseVideoId(Ljava/lang/String;Z)V",
        )
        // Call before any other video id hooks,
        // so they can use VideoInformation and check if the video id is for a Short.
        addPlayerResponseMethodHook(
            Hook.ProtoBufferParameterBeforeVideoId(
                "$EXTENSION_CLASS_DESCRIPTOR->" +
                    "newPlayerResponseSignature(Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;",
            ),
        )

        /*
         * Set the video time method
         */
        timeMethod = context.navigate(playerControllerSetTimeReferenceMatch.method)
            .at(playerControllerSetTimeReferenceMatch.patternMatch!!.startIndex)
            .mutable()

        /*
         * Hook the methods which set the time
         */
        videoTimeHook(EXTENSION_CLASS_DESCRIPTOR, "setVideoTime")

        /*
         * Hook the user playback speed selection
         */
        onPlaybackSpeedItemClickMatch.mutableMethod.apply {
            speedSelectionInsertMethod = this
            val speedSelectionMethodInstructions = this.implementation!!.instructions
            val speedSelectionValueInstructionIndex = speedSelectionMethodInstructions.indexOfFirst {
                it.opcode == Opcode.IGET
            }
            speedSelectionValueRegister =
                getInstruction<TwoRegisterInstruction>(speedSelectionValueInstructionIndex).registerA
            setPlaybackSpeedClassFieldReference =
                getInstruction<ReferenceInstruction>(speedSelectionValueInstructionIndex + 1).reference.toString()
            setPlaybackSpeedMethodReference =
                getInstruction<ReferenceInstruction>(speedSelectionValueInstructionIndex + 2).reference.toString()
            setPlaybackSpeedContainerClassFieldReference =
                getReference(speedSelectionMethodInstructions, -1, Opcode.IF_EQZ)
            speedSelectionInsertIndex = speedSelectionValueInstructionIndex + 1
        }

        userSelectedPlaybackSpeedHook(EXTENSION_CLASS_DESCRIPTOR, "userSelectedPlaybackSpeed")
    }
}

private fun MutableMethod.insert(insertIndex: Int, register: String, descriptor: String) =
    addInstruction(insertIndex, "invoke-static { $register }, $descriptor")

private fun MutableMethod.insertTimeHook(insertIndex: Int, descriptor: String) =
    insert(insertIndex, "p1, p2", descriptor)

/**
 * Hook the player controller.  Called when a video is opened or the current video is changed.
 *
 * Note: This hook is called very early and is called before the video id, video time, video length,
 * and many other data fields are set.
 *
 * @param targetMethodClass The descriptor for the class to invoke when the player controller is created.
 * @param targetMethodName The name of the static method to invoke when the player controller is created.
 */
fun playerControllerOnCreateHook(targetMethodClass: String, targetMethodName: String) =
    playerInitMethod.insert(
        playerInitInsertIndex++,
        "v0",
        "$targetMethodClass->$targetMethodName(Ljava/lang/Object;)V",
    )

/**
 * Hook the video time.
 * The hook is usually called once per second.
 *
 * @param targetMethodClass The descriptor for the static method to invoke when the player controller is created.
 * @param targetMethodName The name of the static method to invoke when the player controller is created.
 */
fun videoTimeHook(targetMethodClass: String, targetMethodName: String) =
    timeMethod.insertTimeHook(
        timeInitInsertIndex++,
        "$targetMethodClass->$targetMethodName(J)V",
    )

private fun getReference(instructions: List<BuilderInstruction>, offset: Int, opcode: Opcode) =
    (instructions[instructions.indexOfFirst { it.opcode == opcode } + offset] as ReferenceInstruction)
        .reference.toString()

/**
 * Hook the video speed selected by the user.
 */
fun userSelectedPlaybackSpeedHook(targetMethodClass: String, targetMethodName: String) =
    speedSelectionInsertMethod.addInstruction(
        speedSelectionInsertIndex++,
        "invoke-static {v$speedSelectionValueRegister}, $targetMethodClass->$targetMethodName(F)V",
    )