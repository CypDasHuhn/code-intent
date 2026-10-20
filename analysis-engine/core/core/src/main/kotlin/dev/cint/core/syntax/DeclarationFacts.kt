package dev.cint.core.syntax

import dev.cint.api.CommentFact
import dev.cint.api.DeclarationFact
import dev.cint.api.DeclarationKind
import dev.cint.api.FunctionFact
import dev.cint.api.PropertyFact
import dev.cint.api.SyntaxNode
import dev.cint.core.syntax.writes.DeclarationWrites
import dev.cint.core.syntax.writes.declarationKind

internal class DeclarationFacts(
    private val kinds: ProfileSyntaxKinds,
    private val calls: CallFacts,
    private val semantics: SemanticFacts,
    private val controlFlow: ControlFlowFacts,
) {
    private val writes = DeclarationWrites(kinds, semantics, controlFlow)

    fun properties(tree: SyntaxTree): Sequence<PropertyFact> =
        tree.root
            .descendants()
            .filter { it.kind in kinds.propertyKinds }
            .map { declaration ->
                PropertyFact(
                    node = declaration,
                    name = variableName(declaration),
                    nameNode = nameNode(declaration),
                    initializer = initializerOf(declaration),
                )
            }

    fun functions(tree: SyntaxTree): Sequence<FunctionFact> =
        tree.root
            .descendants()
            .filter { it.kind in kinds.functionKinds }
            .map { declaration ->
                FunctionFact(
                    node = declaration,
                    name = functionNameNode(declaration)?.text,
                    nameNode = functionNameNode(declaration),
                    body = bodyOf(declaration),
                )
            }

    fun declarations(tree: SyntaxTree): Sequence<DeclarationFact> {
        val references = semantics.referencesByName(tree)
        val propertyEntries =
            properties(tree)
                .map { property -> property to controlFlow.enclosingScope(property.node) }
                .toList()
        val writesByDeclaration = writes.declarationWrites(tree, propertyEntries, references)
        val properties =
            propertyEntries.map { (property, scope) ->
                val annotationNodes = annotationNodes(property.node)
                DeclarationFact(
                    node = property.node,
                    kind = declarationKind(scope),
                    name = property.name,
                    nameNode = property.nameNode,
                    annotations = annotationNodes.map { normalizeAnnotation(it.text) },
                    annotationNodes = annotationNodes,
                    precedingComments = commentBlock(property.node),
                    scope = scope,
                    writes = writesByDeclaration[declarationKey(property.node)].orEmpty(),
                )
            }
        val functions =
            functions(tree).map { function ->
                val annotationNodes = annotationNodes(function.node)
                DeclarationFact(
                    node = function.node,
                    kind = DeclarationKind.FUNCTION,
                    name = function.name,
                    nameNode = function.nameNode,
                    annotations = annotationNodes.map { normalizeAnnotation(it.text) },
                    annotationNodes = annotationNodes,
                    precedingComments = commentBlock(function.node),
                    scope = controlFlow.enclosingScope(function.node),
                )
            }
        return (properties + functions).asSequence()
    }

    private fun declarationKey(node: SyntaxNode): Pair<Int, Int> = node.startByte to node.endByte

    private fun commentBlock(node: SyntaxNode): List<CommentFact> = precedingComments(node).map(::commentFact).toList().reversed()

    fun variableName(declaration: SyntaxNode): String? = nameNode(declaration)?.text

    fun nameNode(declaration: SyntaxNode): SyntaxNode? {
        kinds.fieldNode(declaration, SyntaxFields.VARIABLE_NAME)?.let { return it }
        val variable = declaration.children.firstOrNull { it.kind in kinds.variableKinds }
        if (variable != null) {
            return variable.children.firstOrNull { it.kind in kinds.identifierKinds }
        }
        if (kinds.variableKinds.isEmpty()) {
            return declaration.children.firstOrNull { it.kind in kinds.identifierKinds }
        }
        return null
    }

    fun functionNameNode(declaration: SyntaxNode): SyntaxNode? =
        kinds.fieldNode(declaration, SyntaxFields.FUNCTION_NAME)
            ?: declaration.children.firstOrNull { it.kind in kinds.identifierKinds }

    fun initializerOf(declaration: SyntaxNode): SyntaxNode? {
        kinds.fieldNode(declaration, SyntaxFields.VARIABLE_VALUE)?.let { return it }
        val equals = declaration.children.firstOrNull { it.kind in kinds.equalsKinds } ?: return null
        return declaration.children.firstOrNull { it.startByte >= equals.endByte }
    }

    fun bodyOf(declaration: SyntaxNode): SyntaxNode? =
        kinds.fieldNode(declaration, SyntaxFields.FUNCTION_BODY)
            ?: declaration.children.firstOrNull { it.kind in kinds.functionBodyKinds }

    fun enclosingFunction(node: SyntaxNode): SyntaxNode? = ancestorOf(node, kinds.functionKinds)

    fun enclosingDeclaration(node: SyntaxNode): SyntaxNode? = ancestorOf(node, kinds.propertyKinds + kinds.functionKinds)

    fun declarationHolder(node: SyntaxNode): SyntaxNode? {
        var anchor = node
        var holder = anchor.parent
        while (holder != null && holder.kind in kinds.declarationWrapperKinds) {
            anchor = holder
            holder = anchor.parent
        }
        return holder
    }

    fun isDeclarationName(node: SyntaxNode): Boolean {
        val parent = node.parent ?: return false
        return parent.kind in kinds.propertyKinds + kinds.functionKinds
    }

    fun findFunction(
        tree: SyntaxTree,
        name: String,
    ): SyntaxNode? = functions(tree).firstOrNull { it.name == name }?.node

    fun annotationNodes(declaration: SyntaxNode): List<SyntaxNode> {
        var node: SyntaxNode? = declaration
        while (node != null) {
            val container = node.children.firstOrNull { it.kind in kinds.modifierKinds }
            val annotations =
                container?.children?.filter { it.kind in kinds.annotationKinds }
                    ?: node.children.filter { it.kind in kinds.annotationKinds }
            if (annotations.isNotEmpty()) return annotations
            val ascend = node.kind in kinds.declarationWrapperKinds || node.kind in kinds.propertyKinds
            node = if (ascend) node.parent else null
        }
        return emptyList()
    }

    fun annotationTexts(declaration: SyntaxNode): List<String> = annotationNodes(declaration).map { normalizeAnnotation(it.text) }

    fun commentBefore(node: SyntaxNode): CommentFact? = precedingComments(node).firstOrNull()?.let(::commentFact)

    fun precedingComments(node: SyntaxNode): Sequence<SyntaxNode> =
        sequence {
            var anchor = node
            var wrapper = anchor.parent
            while (wrapper != null && wrapper.kind in kinds.declarationWrapperKinds) {
                anchor = wrapper
                wrapper = anchor.parent
            }
            val parent = anchor.parent ?: return@sequence
            val index = parent.children.indexOfFirst { it.startByte == anchor.startByte && it.endByte == anchor.endByte }
            var current = index - 1
            while (current >= 0 && parent.children[current].kind in kinds.commentKinds) {
                yield(parent.children[current])
                current--
            }
        }

    fun isAnnotatedPure(
        declaration: SyntaxNode,
        patterns: List<String>,
    ): Boolean = annotationTexts(declaration).any { annotation -> patterns.any { matches(annotation, it) } }

    fun isAnnotatedPureCall(
        tree: SyntaxTree,
        call: SyntaxNode,
        patterns: List<String>,
    ): Boolean {
        if (calls.receiverNode(call) != null) return false
        val callee = calls.calleeName(call) ?: return false
        val function = findFunction(tree, callee) ?: return false
        return isAnnotatedPure(function, patterns)
    }

    private fun ancestorOf(
        node: SyntaxNode,
        targetKinds: Set<String>,
    ): SyntaxNode? {
        var current = node.parent
        while (current != null) {
            if (current.kind in targetKinds) return current
            current = current.parent
        }
        return null
    }

    private fun normalizeAnnotation(text: String): String =
        text
            .removePrefix("@")
            .replace(Regex("""^(field|property|get|set|receiver|param|setparam|delegate)\s*:\s*"""), "")
            .replace(Regex("""\s+"""), "")
            .removePrefix("[")
            .removeSuffix("]")

    private fun matches(
        annotation: String,
        pattern: String,
    ): Boolean {
        val expected = normalizeAnnotation(pattern)
        if (annotation.contains('(') || expected.contains('(')) {
            return annotation == expected
        }
        return annotation == expected || annotation.endsWith(".$expected")
    }
}
