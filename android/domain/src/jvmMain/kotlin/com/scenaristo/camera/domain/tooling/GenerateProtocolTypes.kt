package com.scenaristo.camera.domain.tooling

import com.scenaristo.camera.domain.protocol.ClientMessage
import com.scenaristo.camera.domain.protocol.ServerMessage
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
