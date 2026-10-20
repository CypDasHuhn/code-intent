package dev.cint.treesitter

import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxModel

object KotlinSyntaxModel : SyntaxModel by ProfileSyntaxModel(SyntaxProfiles.load("kotlin"))
