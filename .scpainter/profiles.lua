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
        ErrorHandling = {
          meaning = "SHOW",
          bg = "#2d1f1f",
          priority = 50,
        },
        Logging = {
          meaning = "IGNORE",
          bg = "#1f2d1f",
          priority = 10,
        },
        UNTAGGED = {
          meaning = "SHOW",
        },
      },
    },
    hide_logging = {
      extends = "base",
      tags = {
        Logging = {
          meaning = "CONCEAL",
        },
      },
    },
  },
}
