package com.semanticpainter.treesitter

import com.semanticpainter.core.syntax.ProfileSyntaxModel
import com.semanticpainter.core.syntax.SyntaxModel
import com.semanticpainter.core.syntax.SyntaxProfiles

object KotlinSyntaxModel : SyntaxModel by ProfileSyntaxModel(SyntaxProfiles.load("kotlin"))
