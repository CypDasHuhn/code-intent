@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.cint.ipc

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import java.security.MessageDigest

object ProtocolFingerprint {
    const val LEGACY_PROTOCOL_VERSION: Int = 4
    const val CURRENT_PROTOCOL_VERSION: Int = 5

    internal val wireSerializers: List<KSerializer<*>> =
        listOf(
            NoParams.serializer(),
            IpcSpan.serializer(),
            IpcDiagnostic.serializer(),
            IpcProvenance.serializer(),
            IpcAnnotation.serializer(),
            CandidateOrigin.serializer(),
            CandidateOperationKind.serializer(),
            CandidateOperation.serializer(),
            IpcCandidate.serializer(),
            PublishTagsParams.serializer(),
            PublishCandidatesParams.serializer(),
            AnalyzeParams.serializer(),
            AnalyzedFile.serializer(),
            AnalyzeResult.serializer(),
            SuggestParams.serializer(),
            SuggestSelection.serializer(),
            SuggestCancelParams.serializer(),
            SuggestLogParams.serializer(),
            SuggestItem.serializer(),
            SuggestResult.serializer(),
            SuggestProgress.serializer(),
            SuggestHistoryParams.serializer(),
            ConfigOperation.serializer(),
            ConfigParams.serializer(),
            ConfigResult.serializer(),
            StatusResult.serializer(),
            MetricsResult.serializer(),
            DocumentParams.serializer(),
            DocumentCloseParams.serializer(),
            RestrictionsListParams.serializer(),
            RestrictionRemoveParams.serializer(),
            RestrictionOperation.serializer(),
            Restriction.serializer(),
            RestrictionsListResult.serializer(),
            RestrictionRemoveResult.serializer(),
            RestrictionUpsertParams.serializer(),
            CandidateDecisionParams.serializer(),
            ClientInfo.serializer(),
            ServerInfo.serializer(),
            InitializeParams.serializer(),
            InitializeResult.serializer(),
        ).sortedBy { it.descriptor.serialName }

    private val defaultValues: Map<String, String> =
        mapOf(
            "ConfigResult.config" to "{}",
            "InitializeParams.protocol" to ProtocolFingerprint.LEGACY_PROTOCOL_VERSION.toString(),
            "InitializeResult.fingerprint" to "ProtocolFingerprint.CURRENT",
            "InitializeResult.protocol" to ProtocolFingerprint.CURRENT_PROTOCOL_VERSION.toString(),
            "InitializeResult.server" to "ServerInfo()",
            "ServerInfo.name" to "\"code-intent\"",
            "ServerInfo.version" to "\"0.1.0\"",
            "StatusResult.status" to "\"ok\"",
            "SuggestHistoryParams.limit" to "20",
        )

    private val schema: String = buildSchema(wireSerializers, defaultValues)

    val CURRENT: String = sha256(schema)

    fun matches(fingerprint: String?): Boolean = fingerprint == CURRENT

    fun canonicalSchema(): String = schema

    internal fun fingerprintWithDefaults(overrides: Map<String, String>): String =
        sha256(buildSchema(wireSerializers, defaultValues + overrides))

    private fun buildSchema(
        serializers: List<KSerializer<*>>,
        defaults: Map<String, String>,
    ): String =
        buildString {
            append("ipc-schema=")
            append(CURRENT_PROTOCOL_VERSION)
            append('\n')
            append("jsonrpc=jsonrpc,id,method,params,result,error\n")
            append("error=code,message,data.kind,data.details\n")
            for (method in IpcMethods.all.sorted()) {
                append("method=")
                append(method)
                append('\n')
            }
            for (serializer in serializers) {
                append(descriptorSchema(serializer, defaults))
                append('\n')
            }
        }

    private fun descriptorSchema(
        serializer: KSerializer<*>,
        defaults: Map<String, String>,
    ): String {
        val descriptor = serializer.descriptor
        return buildString {
            append(descriptor.serialName)
            append('[')
            append(descriptor.kind)
            append("]=")
            val names = descriptor.elementNames.toList()
            if (descriptor.kind == SerialKind.ENUM) {
                append(names.joinToString(","))
            } else {
                append(
                    names.joinToString(",") { name ->
                        val index = names.indexOf(name)
                        val element = descriptor.getElementDescriptor(index)
                        buildString {
                            append(name)
                            append(':')
                            append(element.serialName)
                            append(':')
                            append(element.kind)
                            append(":optional=")
                            val optional = descriptor.isElementOptional(index)
                            append(optional)
                            append(":nullable=")
                            append(element.isNullable)
                            append(":default=")
                            append(defaultValue(descriptor.serialName, name, element, optional, defaults))
                        }
                    },
                )
            }
        }
    }

    private fun defaultValue(
        serialName: String,
        elementName: String,
        element: kotlinx.serialization.descriptors.SerialDescriptor,
        optional: Boolean,
        defaults: Map<String, String>,
    ): String {
        if (!optional) return "required"
        defaults["$serialName.$elementName"]?.let { return it }
        defaults["${serialName.substringAfterLast('.')}.$elementName"]?.let { return it }
        return when {
            element.isNullable -> "null"
            element.kind == StructureKind.LIST -> "[]"
            element.kind == StructureKind.MAP -> "{}"
            element.kind == SerialKind.ENUM -> "\"${element.getElementName(0)}\""
            element.serialName == "kotlin.String" -> "\"\""
            element.serialName == "kotlin.Boolean" -> "false"
            element.serialName == "kotlin.Int" -> "0"
            element.serialName == "kotlin.Long" -> "0"
            else -> "<?>"
        }
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}

data class ProtocolVersionSupport(
    val version: Int,
    val status: String,
    val requiresFingerprint: Boolean,
    val initializeShape: String,
    val compatibility: String,
)

object ProtocolCompatibility {
    const val MIN_SUPPORTED_PROTOCOL_VERSION: Int = 3

    val matrix: List<ProtocolVersionSupport> =
        listOf(
            ProtocolVersionSupport(
                version = 3,
                status = "legacy",
                requiresFingerprint = false,
                initializeShape = "V1 initialize result",
                compatibility = "accepted without a fingerprint; V2-only initialize fields are removed and handlers emit V1 shapes",
            ),
            ProtocolVersionSupport(
                version = 4,
                status = "legacy",
                requiresFingerprint = false,
                initializeShape = "V1 initialize result",
                compatibility =
                    "accepted without a fingerprint; V2-only initialize fields are removed and handlers emit " +
                        "V1 shapes; restrictions/cache capabilities are advertised",
            ),
            ProtocolVersionSupport(
                version = ProtocolFingerprint.CURRENT_PROTOCOL_VERSION,
                status = "current",
                requiresFingerprint = true,
                initializeShape = "V2 initialize result",
                compatibility = "full V2 wire shapes; fingerprint must equal ProtocolFingerprint.CURRENT",
            ),
        )

    val supportedVersions: List<Int> = matrix.map { it.version }
    val legacyVersions: List<Int> = matrix.filter { it.status == "legacy" }.map { it.version }
    const val LEGACY_REQUIRES_FINGERPRINT: Boolean = false
    const val LEGACY_INITIALIZE_SHAPE: String = "handler-negotiated V1 shape"
    const val LEGACY_REQUEST_ID_SCOPE: String = "recent 256 successful request ids per connection"

    fun isSupported(version: Int): Boolean = matrix.any { it.version == version }

    fun requiresFingerprint(version: Int): Boolean = matrix.firstOrNull { it.version == version }?.requiresFingerprint ?: false
}
