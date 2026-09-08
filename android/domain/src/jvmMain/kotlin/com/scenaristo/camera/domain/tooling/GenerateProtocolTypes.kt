package com.scenaristo.camera.domain.tooling

import com.scenaristo.camera.domain.protocol.ClientMessage
import com.scenaristo.camera.domain.protocol.PROTOCOL_VERSION
import com.scenaristo.camera.domain.lens.RECOMMENDED_FROM
import com.scenaristo.camera.domain.lens.WIDE_BAND
import com.scenaristo.camera.domain.protocol.ServerMessage
import com.scenaristo.camera.domain.recording.TakeName
import com.scenaristo.camera.domain.whitebalance.DEFAULT_KELVIN
import com.scenaristo.camera.domain.whitebalance.LightScenario
import com.scenaristo.camera.domain.whitebalance.presetsFor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.serializer
import java.io.File

/**
 * Generates `web/src/protocol.ts` from the `@Serializable` classes (ADR-0009).
 *
 * ADR-0007 makes those classes the single source of truth and ADR-0009 forbids
 * hand-writing the TypeScript, because two hand-maintained copies of a protocol
 * drift the first time someone is in a hurry — and the drift shows up as a
 * browser silently ignoring a field rather than as an error.
 *
 * It walks `SerialDescriptor`s rather than using a code-generation library, so
 * it adds no dependency: the descriptors already exist, they already know the
 * `@SerialName` values, and anything they cannot express is something the wire
 * format cannot carry either.
 */
@OptIn(ExperimentalSerializationApi::class)
object GenerateProtocolTypes {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: error("usage: GenerateProtocolTypes <output.ts>"))
        out.parentFile?.mkdirs()
        out.writeText(render())
        println("wrote ${out.path}")
    }

    fun render(): String {
        val emitted = LinkedHashMap<String, String>()
        val server = serializer<ServerMessage>().descriptor
        val client = serializer<ClientMessage>().descriptor
        collect(server, emitted)
        collect(client, emitted)

        return buildString {
            appendLine("// Generated from the :domain @Serializable classes. Do not edit (ADR-0009).")
            appendLine("// Regenerate with: cd android && ./gradlew :domain:generateProtocolTypes")
            appendLine()
            // The protocol major, which a client compares against `hello.protocol`
            // and refuses when it does not match (ADR-0007). It is not a
            // @Serializable class, so it does not fall out of the descriptor
            // walk -- but the browser needs the number, and the only other way
            // to give it one is to write it down a second time by hand. That
            // copy went stale the moment ADR-0021 bumped the version: the phone
            // said 2, the browser refused anything but 1, and the remote
            // control could not connect at all.
            appendLine("export const PROTOCOL_VERSION = $PROTOCOL_VERSION;")
            appendLine()
            // PRD 6.4's Kelvin presets, for the same reason as the version
            // above: the remote control has to offer exactly what the phone
            // accepts, and a hand-copied list of six integers is a hand-copied
            // list that goes stale. Only the numbers are generated -- the words
            // beside them ("Daylight in the room", "Lamps only") are UI copy
            // fixed by UI-12 and belong in the bundle.
            appendLine("export const WHITE_BALANCE_PRESETS = {")
            LightScenario.entries.forEach { scenario ->
                appendLine("  ${scenario.name}: [${presetsFor(scenario).joinToString(", ")}],")
            }
            appendLine("} as const;")
            appendLine()
            appendLine("export const DEFAULT_KELVIN = $DEFAULT_KELVIN;")
            appendLine()
            // PRD 6.5's bands, for the same reason as the presets above: the
            // remote applies the same rule to the same focal length, and a
            // browser deciding at 26 mm what the phone decides at 25 is two
            // surfaces disagreeing about the shot in front of them.
            appendLine("export const LENS_WIDE_BAND = { min: ${WIDE_BAND.first}, max: ${WIDE_BAND.last} } as const;")
            appendLine("export const LENS_RECOMMENDED_FROM = $RECOMMENDED_FROM;")
            appendLine()
            // PRD 6.11's download link. A function rather than a constant
            // because the browser needs the whole path, and the alternative --
            // the browser concatenating a prefix and an extension it was handed
            // separately -- is the same hand-copied assembly this file exists to
            // remove, just spread over two lines. Both halves come from
            // TakeName, so the route and the link cannot drift apart.
            appendLine("export function takePath(name: string): string {")
            appendLine("  return `${TakeName.PATH_PREFIX}\${name}.${TakeName.EXTENSION}`;")
            appendLine("}")
            appendLine()
            emitted.values.forEach { appendLine(it) }
            appendLine(union("ServerMessage", server))
            appendLine(union("ClientMessage", client))
        }
    }

    /** A sealed hierarchy becomes a discriminated union, which is how TypeScript narrows on `type`. */
    private fun union(name: String, descriptor: SerialDescriptor): String {
        val members = subclasses(descriptor).map { simpleName(it) }
        return "export type $name = ${members.joinToString(" | ")};\n"
    }

    private fun collect(descriptor: SerialDescriptor, into: MutableMap<String, String>) {
        when (descriptor.kind) {
            is PolymorphicKind -> subclasses(descriptor).forEach { collect(it, into) }
            is StructureKind.CLASS -> emitInterface(descriptor, into)
            SerialKind.ENUM -> emitEnum(descriptor, into)
            is StructureKind.LIST -> collect(descriptor.getElementDescriptor(0), into)
            else -> Unit
        }
    }

    private fun emitInterface(descriptor: SerialDescriptor, into: MutableMap<String, String>) {
        val name = simpleName(descriptor)
        if (name in into) return
        into[name] = "" // Guard against recursion before the children are walked.

        val fields = buildString {
            for (i in 0 until descriptor.elementsCount) {
                val child = descriptor.getElementDescriptor(i)
                collect(child, into)
                val optional = child.isNullable || descriptor.isElementOptional(i)
                append("  ").append(descriptor.getElementName(i))
                if (optional) append("?")
                append(": ").append(tsType(child)).appendLine(";")
            }
        }
        into[name] = buildString {
            appendLine("export interface $name {")
            discriminatorLine(descriptor)?.let { appendLine(it) }
            append(fields)
            appendLine("}")
        }
    }

    /**
     * `@SerialName("hello")` on a sealed subclass is the value of the `type`
     * field the server actually sends, so it becomes a literal type here — that
     * literal is what lets a client `switch (msg.type)` and get exhaustiveness
     * checking for free.
     */
    private fun discriminatorLine(descriptor: SerialDescriptor): String? {
        val serialName = descriptor.serialName
        // Only sealed subclasses carry a short @SerialName; plain data classes
        // keep their fully qualified one.
        if ('.' in serialName) return null
        return """  type: "$serialName";"""
    }

    private fun emitEnum(descriptor: SerialDescriptor, into: MutableMap<String, String>) {
        val name = simpleName(descriptor)
        if (name in into) return
        val values = (0 until descriptor.elementsCount).joinToString(" | ") { "\"${descriptor.getElementName(it)}\"" }
        into[name] = "export type $name = $values;\n"
    }

    private fun tsType(descriptor: SerialDescriptor): String {
        val base = when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> "boolean"
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT,
            PrimitiveKind.LONG, PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE,
            -> "number"
            is StructureKind.LIST -> "${tsType(descriptor.getElementDescriptor(0))}[]"
            else -> simpleName(descriptor)
        }
        return if (descriptor.isNullable) "$base | null" else base
    }

    private fun subclasses(descriptor: SerialDescriptor): List<SerialDescriptor> =
        // A sealed descriptor's second element is the "value" slot, whose own
        // elements are the subclasses.
        descriptor.getElementDescriptor(1).elementDescriptors.toList()

    /**
     * The TypeScript name for a descriptor.
     *
     * Sealed members are named after their `@SerialName` — `"state"` — while
     * ordinary classes carry a fully qualified one. Capitalising both would put
     * `StateMessage` and the `State` document under the same name, and the
     * collision is silent: the emitted `state: State` would refer to itself and
     * the real document would never be emitted at all. Sealed members therefore
     * take a `Message` suffix, which also reads the way the union does.
     */
    private fun simpleName(descriptor: SerialDescriptor): String {
        val serialName = descriptor.serialName.removeSuffix("?")
        val short = serialName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        return if ('.' in serialName) short else "${short}Message"
    }
}
