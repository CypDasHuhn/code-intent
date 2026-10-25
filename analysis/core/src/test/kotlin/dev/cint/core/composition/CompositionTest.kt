package dev.cint.core.composition

import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Role
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.DeclarationKind
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.IfFact
import dev.cint.contracts.facts.PropertyFact
import dev.cint.contracts.facts.ReturnFact
import dev.cint.contracts.facts.StatementFact
import dev.cint.contracts.facts.StatementKind
import dev.cint.contracts.facts.TryFact
import dev.cint.contracts.facts.ValueFact
import dev.cint.contracts.facts.ValueKind
import dev.cint.core.CoreContributors
import dev.cint.core.store.AnnotationStore
import dev.cint.core.support.contribution
import dev.cint.core.support.facts
import dev.cint.core.support.node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompositionTest {
    @Test
    fun derivativeUsePromotesAClassifiedPropertyInitializer() {
        val property = node("p", "property", 0, 20, "value = x", childIds = listOf("name", "init"))
        val name = node("name", "identifier", 0, 5, "value", parentId = "p")
        val initializer = node("init", "identifier", 8, 9, "x", parentId = "p")
        val root = node("root", "program", 0, 100, childIds = listOf("p"))
        val facts =
            facts("root", listOf(root, property, name, initializer)) {
                copy(
                    properties =
                        listOf(
                            PropertyFact(
                                nodeId = "p",
                                name = "value",
                                nameNodeId = "name",
                                initializerId = "init",
                            ),
                        ),
                )
            }

        val composed =
            Composition().compose(
                sourceIntents = setOf(Intents.LOOKUP),
                facts = facts,
                sources = listOf(contribution("source:lookup", Intents.LOOKUP, initializer)),
            )

        val derivative = composed.single()
        assertEquals(Role.DERIVATIVE_USE, derivative.role)
        assertEquals(setOf(Intents.LOOKUP), derivative.intents)
        assertEquals(CoreContributors.DERIVATIVE_USE, derivative.rule)
        assertEquals(property.startByte to property.endByte, derivative.startByte to derivative.endByte)
    }

    @Test
    fun returnCategoryUsesTheCertifyingGuardAndNeutralRejectionFact() {
        val root = node("root", "program", 0, 100, childIds = listOf("if"))
        val ifNode = node("if", "if", 0, 90, childIds = listOf("cond", "then", "else"))
        val condition = node("cond", "identifier", 3, 10, "isValid", parentId = "if")
        val thenBody = node("then", "block", 15, 50, parentId = "if", childIds = listOf("return"))
        val returnNode = node("return", "return", 20, 35, parentId = "then", childIds = listOf("expr"))
        val expression = node("expr", "identifier", 27, 32, "false", parentId = "return")
        val elseBody = node("else", "block", 55, 85, parentId = "if")
        val facts =
            facts("root", listOf(root, ifNode, condition, thenBody, returnNode, expression, elseBody)) {
                copy(
                    ifs =
                        listOf(
                            IfFact(
                                nodeId = "if",
                                conditionId = "cond",
                                thenBodyId = "then",
                                elseBodyId = "else",
                                elseKeywordId = null,
                            ),
                        ),
                    returns =
                        listOf(
                            ReturnFact(
                                nodeId = "return",
                                expressionId = "expr",
                                guardIds = listOf("cond"),
                            ),
                        ),
                    values = listOf(ValueFact(nodeId = "expr", kind = ValueKind.LITERAL, rejection = true)),
                )
            }

        val composed =
            Composition().compose(
                sourceIntents = setOf(Intents.VALIDATION),
                facts = facts,
                sources =
                    listOf(
                        contribution(
                            "source:validation",
                            Intents.VALIDATION,
                            condition,
                            certifiesReturn = true,
                        ),
                    ),
            )

        val category = composed.single { it.rule == CoreContributors.RETURN_CATEGORY }
        assertEquals(Role.SCAFFOLD, category.role)
        assertEquals(setOf(Intents.VALIDATION), category.intents)
        assertEquals(returnNode.startByte to returnNode.endByte, category.startByte to category.endByte)
    }

    @Test
    fun scaffoldUsesExplicitStatementsAndIgnoresCommentChildren() {
        val root = node("root", "program", 0, 100, childIds = listOf("fn"))
        val function = node("fn", "function", 0, 100, parentId = "root", childIds = listOf("body"))
        val body =
            node(
                "body",
                "block",
                10,
                80,
                parentId = "fn",
                childIds = listOf("comment", "s1", "s2"),
            )
        val comment = node("comment", "comment", 11, 19, "// note", parentId = "body")
        val first = node("s1", "call", 20, 30, parentId = "body")
        val second = node("s2", "call", 40, 50, parentId = "body")
        val facts =
            facts("root", listOf(root, function, body, comment, first, second)) {
                copy(
                    functions =
                        listOf(
                            FunctionFact(
                                nodeId = "fn",
                                name = "f",
                                nameNodeId = null,
                                bodyId = "body",
                            ),
                        ),
                    statements =
                        listOf(
                            StatementFact(nodeId = "s1", containerId = "body"),
                            StatementFact(nodeId = "s2", containerId = "body"),
                        ),
                )
            }

        val composed =
            Composition().compose(
                sourceIntents = setOf(Intents.LOGGING),
                facts = facts,
                sources =
                    listOf(
                        contribution("source:logging", Intents.LOGGING, first),
                        contribution("source:logging", Intents.LOGGING, second),
                    ),
            )

        val scaffold = composed.single { it.rule == CoreContributors.SCAFFOLD }
        assertEquals(Role.SCAFFOLD, scaffold.role)
        assertEquals(setOf(Intents.LOGGING), scaffold.intents)
        assertEquals(function.startByte to function.endByte, scaffold.startByte to scaffold.endByte)
    }

    @Test
    fun partialFunctionBodyDoesNotBecomeAScaffold() {
        val root = node("root", "program", 0, 100, childIds = listOf("fn"))
        val function = node("fn", "function", 0, 100, parentId = "root", childIds = listOf("body"))
        val body = node("body", "block", 10, 80, parentId = "fn", childIds = listOf("s1", "s2"))
        val first = node("s1", "call", 20, 30, parentId = "body")
        val second = node("s2", "call", 40, 50, parentId = "body")
        val facts =
            facts("root", listOf(root, function, body, first, second)) {
                copy(
                    functions =
                        listOf(
                            FunctionFact(
                                nodeId = "fn",
                                name = "f",
                                nameNodeId = null,
                                bodyId = "body",
                            ),
                        ),
                    statements =
                        listOf(
                            StatementFact(nodeId = "s1", containerId = "body"),
                            StatementFact(nodeId = "s2", containerId = "body"),
                        ),
                )
            }

        val composed =
            Composition().compose(
                sourceIntents = setOf(Intents.LOGGING),
                facts = facts,
                sources = listOf(contribution("source:logging", Intents.LOGGING, first)),
            )

        assertTrue(composed.none { it.rule == CoreContributors.SCAFFOLD })
    }

    @Test
    fun statementExpressionFallbackCarriesIntents() {
        val root = node("root", "program", 0, 100, childIds = listOf("fn"))
        val function = node("fn", "function", 0, 100, parentId = "root", childIds = listOf("body"))
        val body = node("body", "block", 10, 80, parentId = "fn", childIds = listOf("s1"))
        val statement = node("s1", "wrapper", 20, 30, parentId = "body", childIds = listOf("expr"))
        val expression = node("expr", "call", 22, 28, parentId = "s1")
        val facts =
            facts("root", listOf(root, function, body, statement, expression)) {
                copy(
                    functions =
                        listOf(
                            FunctionFact(
                                nodeId = "fn",
                                name = "f",
                                nameNodeId = null,
                                bodyId = "body",
                            ),
                        ),
                    statements =
                        listOf(
                            StatementFact(
                                nodeId = "s1",
                                containerId = "body",
                                expressionId = "expr",
                            ),
                        ),
                )
            }

        val composed =
            Composition().compose(
                sourceIntents = setOf(Intents.LOGGING),
                facts = facts,
                sources = listOf(contribution("source:logging", Intents.LOGGING, expression)),
            )

        val scaffold = composed.single { it.rule == CoreContributors.SCAFFOLD }
        assertEquals(setOf(Intents.LOGGING), scaffold.intents)
    }

    @Test
    fun exactFalseReturnIsNeutralAndDoesNotBlockAScaffold() {
        val root = node("root", "program", 0, 100, childIds = listOf("fn"))
        val function = node("fn", "function", 0, 100, parentId = "root", childIds = listOf("body"))
        val body = node("body", "block", 10, 80, parentId = "fn", childIds = listOf("ret", "s2"))
        val returnNode = node("ret", "return", 20, 30, parentId = "body", childIds = listOf("expr"))
        val expression = node("expr", "literal", 27, 32, "false", parentId = "ret")
        val second = node("s2", "call", 40, 50, parentId = "body")
        val facts =
            facts("root", listOf(root, function, body, returnNode, expression, second)) {
                copy(
                    functions =
                        listOf(
                            FunctionFact(
                                nodeId = "fn",
                                name = "f",
                                nameNodeId = null,
                                bodyId = "body",
                            ),
                        ),
                    statements =
                        listOf(
                            StatementFact("ret", "body", "expr", StatementKind.RETURN),
                            StatementFact("s2", "body"),
                        ),
                    returns = listOf(ReturnFact(nodeId = "ret", expressionId = "expr")),
                    values =
                        listOf(
                            ValueFact(
                                nodeId = "expr",
                                kind = ValueKind.LITERAL,
                                booleanValue = false,
                            ),
                        ),
                )
            }

        val composed =
            Composition().compose(
                sourceIntents = setOf(Intents.LOGGING),
                facts = facts,
                sources = listOf(contribution("source:logging", Intents.LOGGING, second)),
            )

        val scaffold = composed.single { it.rule == CoreContributors.SCAFFOLD }
        assertEquals(setOf(Intents.LOGGING), scaffold.intents)
    }

    @Test
    fun continueAndBreakExitsAreNeutralAndDoNotBlockAScaffold() {
        val root = node("root", "program", 0, 100, childIds = listOf("fn"))
        val function = node("fn", "function", 0, 100, parentId = "root", childIds = listOf("body"))
        val body =
            node("body", "block", 10, 80, parentId = "fn", childIds = listOf("cont", "brk", "s3"))
        val continueNode = node("cont", "continue", 20, 25, parentId = "body")
        val breakNode = node("brk", "break", 30, 35, parentId = "body")
        val third = node("s3", "call", 40, 50, parentId = "body")
        val facts =
            facts("root", listOf(root, function, body, continueNode, breakNode, third)) {
                copy(
                    functions =
                        listOf(
                            FunctionFact(
                                nodeId = "fn",
                                name = "f",
                                nameNodeId = null,
                                bodyId = "body",
                            ),
                        ),
                    statements =
                        listOf(
                            StatementFact("cont", "body", kind = StatementKind.CONTINUE),
                            StatementFact("brk", "body", kind = StatementKind.BREAK),
                            StatementFact("s3", "body"),
                        ),
                )
            }

        val composed =
            Composition().compose(
                sourceIntents = setOf(Intents.LOGGING),
                facts = facts,
                sources = listOf(contribution("source:logging", Intents.LOGGING, third)),
            )

        val scaffold = composed.single { it.rule == CoreContributors.SCAFFOLD }
        assertEquals(setOf(Intents.LOGGING), scaffold.intents)
    }

    @Test
    fun contradictoryValueFactsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            ValueFact(nodeId = "expr", rejection = true)
        }
        assertFailsWith<IllegalArgumentException> {
            ValueFact(nodeId = "expr", kind = ValueKind.IDENTIFIER, booleanValue = true)
        }
    }

    @Test
    fun wholeTryScaffoldDoesNotDependOnCoverageThreshold() {
        val root = node("root", "program", 0, 100, childIds = listOf("try"))
        val tryNode = node("try", "try", 0, 60, parentId = "root", childIds = listOf("tryBody", "catch"))
        val tryBody = node("tryBody", "block", 4, 30, parentId = "try", childIds = listOf("s1"))
        val first = node("s1", "call", 10, 20, parentId = "tryBody")
        val catchNode = node("catch", "catch", 30, 55, parentId = "try", childIds = listOf("s2"))
        val second = node("s2", "call", 38, 48, parentId = "catch")
        val facts =
            facts("root", listOf(root, tryNode, tryBody, first, catchNode, second)) {
                copy(
                    tries =
                        listOf(
                            TryFact(
                                nodeId = "try",
                                bodyId = "tryBody",
                                catchIds = listOf("catch"),
                                finallyBlockId = null,
                                headerStartId = null,
                                headerEndId = null,
                            ),
                        ),
                    statements =
                        listOf(
                            StatementFact(nodeId = "s1", containerId = "tryBody"),
                            StatementFact(nodeId = "s2", containerId = "catch"),
                        ),
                )
            }

        val composed =
            Composition().compose(
                sourceIntents = setOf(Intents.ERROR_HANDLING),
                facts = facts,
                sources =
                    listOf(
                        contribution("source:error", Intents.ERROR_HANDLING, first),
                        contribution("source:error", Intents.ERROR_HANDLING, second),
                    ),
            )

        val scaffold =
            composed.single {
                it.rule == CoreContributors.SCAFFOLD && it.startByte == tryNode.startByte
            }
        assertEquals(setOf(Intents.ERROR_HANDLING), scaffold.intents)
    }

    @Test
    fun derivativeFunctionPromotionUsesQualifiedPureAnnotation() {
        val root = node("root", "program", 0, 100, childIds = listOf("p", "fn"))
        val property = node("p", "property", 0, 20, childIds = listOf("name", "init", "ref"))
        val name = node("name", "identifier", 0, 5, "value", parentId = "p")
        val initializer = node("init", "identifier", 8, 9, "x", parentId = "p")
        val reference = node("ref", "identifier", 12, 13, "f", parentId = "p")
        val function = node("fn", "function", 30, 60, parentId = "root", childIds = listOf("fname"))
        val functionName = node("fname", "identifier", 30, 31, "f", parentId = "fn")
        val facts =
            facts(
                "root",
                listOf(root, property, name, initializer, reference, function, functionName),
            ) {
                copy(
                    properties =
                        listOf(
                            PropertyFact(
                                nodeId = "p",
                                name = "value",
                                nameNodeId = "name",
                                initializerId = "init",
                            ),
                        ),
                    functions =
                        listOf(
                            FunctionFact(
                                nodeId = "fn",
                                name = "f",
                                nameNodeId = "fname",
                                bodyId = null,
                            ),
                        ),
                    references =
                        listOf(
                            dev.cint.contracts.facts
                                .ReferenceFact("f", listOf("ref")),
                        ),
                    declarations =
                        listOf(
                            DeclarationFact(
                                nodeId = "p",
                                kind = DeclarationKind.VARIABLE,
                                name = "value",
                                nameNodeId = "name",
                            ),
                            DeclarationFact(
                                nodeId = "fn",
                                kind = DeclarationKind.FUNCTION,
                                name = "f",
                                nameNodeId = "fname",
                                annotations = listOf("com.example.Pure"),
                            ),
                        ),
                )
            }

        val composed =
            Composition().compose(
                sourceIntents = setOf(Intents.LOOKUP),
                facts = facts,
                sources = listOf(contribution("source:lookup", Intents.LOOKUP, initializer)),
                config = CompositionConfig(pureAnnotations = listOf("Pure")),
            )

        val promoted = composed.single { it.startByte == function.startByte }
        assertEquals(Role.DERIVATIVE_USE, promoted.role)
        assertEquals(setOf(Intents.LOOKUP), promoted.intents)
    }

    @Test
    fun pureAnnotationMatchingIsExactOrQualified() {
        assertTrue(pureAnnotationMatches("com.example.Pure", "Pure"))
        assertTrue(pureAnnotationMatches("@Pure", "Pure"))
        assertTrue(pureAnnotationMatches("Pure()", " Pure ( ) "))
        assertFalse(pureAnnotationMatches("Impure", "Pure"))
        assertFalse(pureAnnotationMatches("PureFactory", "Pure"))
    }

    @Test
    fun oscillatingCompositionProducerFailsWithNamedError() {
        val store = AnnotationStore()
        val first = node("a", "identifier", 0, 1)
        val second = node("b", "identifier", 1, 2)
        val a = listOf(contribution("source:a", Intents.LOGGING, first))
        val b = listOf(contribution("source:b", Intents.LOOKUP, second))
        var index = 0

        val error =
            assertFailsWith<NonConvergentComposition> {
                settleComposition(store, "osc", 10L) {
                    if (index++ % 2 == 0) a else b
                }
            }

        assertEquals("osc", error.key)
        assertTrue(error.message.orEmpty().contains("repeated state"))
    }

    @Test
    fun compositionWithoutASyntaxTreeIsEmpty() {
        val composed =
            Composition().compose(
                sourceIntents = setOf(Intents.LOGGING),
                facts =
                    dev.cint.contracts.facts
                        .FactBatch(syntaxTree = null),
                sources = emptyList(),
            )

        assertTrue(composed.isEmpty())
    }
}
