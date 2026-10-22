package dev.cint.core.user.support

internal val CROSS_WRITER_LUA_SCRIPT =
    """
    vim.opt.runtimepath:prepend(vim.env.CINT_CROSS_REPO_ROOT .. "/nvim-renderer")
    vim.opt.swapfile = false
    vim.opt.shadafile = "NONE"

    local cint = require("cint")
    require("cint.commands").register(cint)
    local annotations = require("cint.ops.annotations")
    local state = require("cint.core.state")

    local function required(name)
      local value = vim.env[name]
      assert(value and value ~= "", "missing " .. name)
      return value
    end

    local function optional(name)
      local value = vim.env[name]
      if value == nil or value == "" then
        return nil
      end
      return value
    end

    local function write(path, text)
      local file = assert(io.open(path, "w"))
      file:write(text)
      file:close()
    end

    local root = required("CINT_CROSS_ROOT")
    local file = required("CINT_CROSS_FILE")
    local mode = required("CINT_CROSS_MODE")
    local line = tonumber(required("CINT_CROSS_LINE"))
    local col = tonumber(required("CINT_CROSS_COL"))
    local intent = optional("CINT_CROSS_INTENT")
    local note = optional("CINT_CROSS_NOTE")
    local started = optional("CINT_CROSS_STARTED")
    local ready = optional("CINT_CROSS_READY")
    local gate = optional("CINT_CROSS_GATE")
    local blocked = optional("CINT_CROSS_BLOCKED")
    local interval_dir = optional("CINT_CROSS_INTERVAL_DIR")
    local conflict_dir = optional("CINT_CROSS_CONFLICT_DIR")
    local writer_id = optional("CINT_CROSS_WRITER_ID")
    local current_token = nil
    local conflict_written = false
    local blocked_written = false

    local function interval_record(token, phase)
      assert(interval_dir and writer_id, "interval environment is incomplete")
      local target = interval_dir .. "/" .. writer_id .. "-" .. token .. "." .. phase
      local temporary = target .. ".tmp-" .. tostring(vim.uv.os_getpid())
      write(temporary, table.concat({
        "writer=" .. writer_id,
        "pid=" .. tostring(vim.uv.os_getpid()),
        "token=" .. token,
        "phase=" .. phase,
        "nanos=" .. string.format("%.0f", vim.uv.hrtime()),
      }, "\n") .. "\n")
      local ok, err = vim.uv.fs_rename(temporary, target)
      assert(ok, tostring(err))
    end

    local function interval_enter(lock, token)
      current_token = token
      interval_record(token, "enter")
    end

    local function interval_exit()
      if current_token == nil then
        return
      end
      interval_record(current_token, "exit")
      current_token = nil
    end

    local function on_conflict(path)
      if conflict_dir and writer_id and not conflict_written then
        conflict_written = true
        write(conflict_dir .. "/" .. writer_id .. ".conflict", "1\n")
      end
      if blocked and not blocked_written then
        blocked_written = true
        write(blocked, "blocked\n")
      end
    end

    local function has_note(buf, expected)
      for _, record in ipairs(annotations.annotations(buf)) do
        if record.note == expected then
          return true
        end
      end
      return false
    end

    local function run()
      cint.setup({ root = root, auto_render = false })
      state.live_failed = true
      vim.cmd.edit(vim.fn.fnameescape(file))
      local buf = vim.api.nvim_get_current_buf()
      vim.api.nvim_win_set_cursor(0, { line, col })

      local hooks = {}
      if interval_dir and writer_id then
        hooks.after_acquire = interval_enter
        hooks.before_release = interval_exit
      end
      if conflict_dir or blocked then
        hooks.before_conflict = on_conflict
      end
      if next(hooks) ~= nil then
        annotations._set_lock_hooks(hooks)
      end

      if started then
        write(started, "started\n")
      end
      if ready then
        write(ready, "ready\n")
      end
      if gate then
        assert(
          vim.wait(15000, function()
            return vim.uv.fs_stat(gate) ~= nil
          end, 5),
          "timed out waiting for the cross-writer gate"
        )
      end

      if mode == "annotate" then
        vim.cmd("CintAnnotate " .. intent .. " " .. note)
        local persisted = nil
        for _, record in ipairs(annotations.annotations(buf)) do
          if record.note == note then
            persisted = record
          end
        end
        assert(persisted, "CintAnnotate did not persist the expected note")
        assert(vim.tbl_contains(persisted.intents, intent), "CintAnnotate persisted the wrong intent")
      elseif mode == "annotate-unannotate" then
        vim.cmd("CintAnnotate " .. intent .. " " .. note)
        local before = annotations.annotations(buf)
        local persisted = false
        for _, record in ipairs(before) do
          if record.note == note then
            persisted = true
          end
        end
        assert(persisted, "CintAnnotate did not persist the expected note")
        vim.cmd("CintUnannotate")
        assert(not has_note(buf, note), "CintUnannotate did not remove the annotated record")
      elseif mode == "unannotate" then
        assert(note, "CintUnannotate needs an expected note")
        assert(has_note(buf, note), "CintUnannotate needs a record to remove")
        vim.cmd("CintUnannotate")
        assert(not has_note(buf, note), "CintUnannotate did not remove the expected record")
      else
        error("unknown mode " .. mode)
      end
    end

    local ok, err = xpcall(run, debug.traceback)
    if not ok then
      io.stderr:write(tostring(err) .. "\n")
      vim.cmd("cquit 1")
    end
    vim.cmd("qa!")
    """.trimIndent()
