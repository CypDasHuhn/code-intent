return {
	default = "base",
	profiles = {
		base = {
			resolve = {
				default = "SHOW",
				precedence = { "SHOW", "HIGHLIGHT", "IGNORE", "CONCEAL" },
			},
			rules = {
				CONCEAL = {
					conceal = {
						lines = true,
					},
				},
				HIGHLIGHT = {
					highlight = true,
				},
				IGNORE = {
					ignore = {
						fg = "#555555",
					},
				},
				SHOW = {
					highlight = true,
				},
			},
			tags = {
				ERROR_HANDLING = {
					meaning = "IGNORE",
					priority = 50,
				},
				LOGGING = {
					meaning = "IGNORE",
					priority = 10,
				},
				UNTAGGED = {
					meaning = "SHOW",
				},
				BOOKKEEPING = {
					meaning = "IGNORE",
				},
				VALIDATION = {
					meaning = "IGNORE",
				},
			},
		},
		hide_logging = {
			extends = "base",
			tags = {
				LOGGING = {
					meaning = "CONCEAL",
				},
			},
		},
	},
	shards = {
		conceal_logging = {
			tags = { LOGGING = { meaning = "CONCEAL" } },
		},
		highlight_logging = {
			tags = { LOGGING = { meaning = "HIGHLIGHT" } },
		},
	},
}
