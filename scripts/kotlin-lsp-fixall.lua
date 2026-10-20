local function fail(message)
  print(message)
  vim.cmd('cq')
end

local function find_root(path)
  local dir = vim.fn.isdirectory(path) == 1 and path or vim.fs.dirname(path)
  while dir and dir ~= vim.fs.dirname(dir) do
    for _, marker in ipairs({ 'settings.gradle.kts', 'settings.gradle', 'gradlew', '.git' }) do
      if vim.fn.filereadable(dir .. '/' .. marker) == 1 or vim.fn.isdirectory(dir .. '/' .. marker) == 1 then
        return dir
      end
    end
    dir = vim.fs.dirname(dir)
  end
  return vim.uv.cwd()
end

local function collect_files(root)
  if vim.fn.filereadable(root) == 1 then
    return { vim.fn.fnamemodify(root, ':p') }
  end
  if vim.fn.isdirectory(root) ~= 1 then
    return {}
  end

  local files = {}
  for _, pattern in ipairs({ '/**/*.kt', '/**/*.kts' }) do
    for _, file in ipairs(vim.fn.glob(root .. pattern, false, true)) do
      if not file:match('/build/') and not file:match('/%.gradle/') and not file:match('/%.git/') then
        files[#files + 1] = vim.fn.fnamemodify(file, ':p')
      end
    end
  end
  table.sort(files)
  return files
end

local function lsp_diagnostic(diagnostic)
  local original = diagnostic.user_data and diagnostic.user_data.lsp
  if original then
    return original
  end
  return {
    range = {
      start = { line = diagnostic.lnum, character = diagnostic.col },
      ['end'] = { line = diagnostic.end_lnum, character = diagnostic.end_col },
    },
    severity = diagnostic.severity,
    code = diagnostic.code,
    source = diagnostic.source,
    message = diagnostic.message,
  }
end

local function diagnostic_key(diagnostic)
  if diagnostic.range then
    local range = diagnostic.range
    return table.concat({
      range.start.line,
      range.start.character,
      range['end'].line,
      range['end'].character,
      diagnostic.message or '',
    }, ':')
  end
  return table.concat({
    diagnostic.lnum,
    diagnostic.col,
    diagnostic.end_lnum,
    diagnostic.end_col,
    diagnostic.message or '',
  }, ':')
end

local function first_action(client, bufnr, diagnostic)
  local params = vim.lsp.util.make_range_params(0, client.offset_encoding)
  params.textDocument = { uri = vim.uri_from_bufnr(bufnr) }
  params.range = diagnostic.range
  params.context = {
    diagnostics = { diagnostic },
    triggerKind = 1,
  }

  local responses = vim.lsp.buf_request_sync(bufnr, 'textDocument/codeAction', params, 5000)
  local response = responses and responses[client.id]
  local actions = response and response.result
  if type(actions) ~= 'table' then
    return nil
  end

  for _, action in ipairs(actions) do
    if action.edit or action.command then
      return action
    end
  end
  return nil
end

local function apply_action(client, action)
  if action.edit then
    vim.lsp.util.apply_workspace_edit(action.edit, client.offset_encoding)
  end
  if action.command then
    if client.exec_cmd then
      client:exec_cmd(action.command)
    else
      vim.lsp.buf.execute_command(action.command)
    end
  end
end

local function pull_diagnostics(client, bufnr)
  local responses = vim.lsp.buf_request_sync(bufnr, 'textDocument/diagnostic', {
    textDocument = { uri = vim.uri_from_bufnr(bufnr) },
  }, 5000)
  local response = responses and responses[client.id]
  local result = response and response.result
  if type(result) == 'table' and type(result.items) == 'table' then
    return result.items
  end
  return nil
end

local function current_diagnostics(client, bufnr)
  local pulled = pull_diagnostics(client, bufnr)
  if pulled ~= nil then
    return pulled
  end
  local diagnostics = {}
  for _, diagnostic in ipairs(vim.diagnostic.get(bufnr)) do
    diagnostics[#diagnostics + 1] = lsp_diagnostic(diagnostic)
  end
  return diagnostics
end

local root_arg = vim.env.LSP_FIX_ROOT
if not root_arg or root_arg == '' then
  fail('LSP_FIX_ROOT is required')
end
local max_actions = tonumber(vim.env.LSP_FIX_MAX or '25') or 25
if max_actions < 1 then
  fail('LSP_FIX_MAX must be positive')
end
local attach_timeout = tonumber(vim.env.LSP_FIX_ATTACH_TIMEOUT or '60000') or 60000
local readiness_timeout = tonumber(vim.env.LSP_FIX_READINESS_TIMEOUT or '60000') or 60000
local first_file_timeout = tonumber(vim.env.LSP_FIX_FIRST_FILE_TIMEOUT or '5000') or 5000
local file_timeout = tonumber(vim.env.LSP_FIX_FILE_TIMEOUT or '1500') or 1500
local action_timeout = tonumber(vim.env.LSP_FIX_ACTION_TIMEOUT or '1500') or 1500
local debug = vim.env.LSP_FIX_DEBUG == '1'

local files = collect_files(root_arg)
if #files == 0 then
  fail('no Kotlin files found under ' .. root_arg)
end

local root = find_root(files[1])
local published = {}
local active_progress = {}
local saw_progress = false
local default_publish = vim.lsp.handlers['textDocument/publishDiagnostics']
local config = {
  name = 'kotlin_lsp',
  cmd = { 'kotlin-lsp', '--stdio' },
  root_dir = root,
  capabilities = vim.lsp.protocol.make_client_capabilities(),
  handlers = {
    ['textDocument/publishDiagnostics'] = function(err, result, ctx)
      if result and result.uri then
        published[result.uri] = true
      end
      if default_publish then
        default_publish(err, result, ctx)
      end
    end,
    ['$/progress'] = function(_, result)
      local value = result and result.value
      if not value then
        return
      end
      if value.kind == 'begin' then
        active_progress[result.token] = true
        saw_progress = true
      elseif value.kind == 'end' then
        active_progress[result.token] = nil
      end
    end,
  },
}

local client
local total_applied = 0
local changed_files = 0

for _, file in ipairs(files) do
  vim.cmd('edit ' .. vim.fn.fnameescape(file))
  vim.bo.filetype = 'kotlin'

  local bufnr = vim.api.nvim_get_current_buf()
  local client_id = vim.lsp.start(config, { bufnr = bufnr })
  if not client_id then
    print('skip: could not start kotlin_lsp for ' .. file)
    break
  end

  local started = vim.uv.hrtime()
  if not vim.wait(attach_timeout, function()
    return #vim.lsp.get_clients({ bufnr = bufnr, name = 'kotlin_lsp' }) > 0
  end, 100) then
    print('skip: kotlin_lsp did not attach for ' .. file)
    break
  end

  local first_client = client == nil
  client = vim.lsp.get_clients({ bufnr = bufnr, name = 'kotlin_lsp' })[1]
  if not client then
    print('skip: kotlin_lsp is not attached to ' .. file)
    break
  end
  if first_client then
    if not vim.wait(readiness_timeout, function()
      return saw_progress and next(active_progress) == nil
    end, 50) then
      print('warning: kotlin_lsp readiness timed out')
    end
    if debug then
      print(('ready after %.0fms'):format((vim.uv.hrtime() - started) / 1e6))
    end
  end

  local uri = vim.uri_from_fname(file)
  published[uri] = nil
  vim.wait(first_client and first_file_timeout or file_timeout, function()
    return published[uri] == true or #vim.diagnostic.get(bufnr) > 0
  end, 25)

  local attempted = {}
  local applied = 0

  for _ = 1, max_actions do
    local diagnostics = current_diagnostics(client, bufnr)
    table.sort(diagnostics, function(a, b)
      if a.severity ~= b.severity then
        return (a.severity or 0) < (b.severity or 0)
      end
      local aline = a.range and a.range.start.line or a.lnum
      local bline = b.range and b.range.start.line or b.lnum
      if aline ~= bline then
        return aline < bline
      end
      local acol = a.range and a.range.start.character or a.col
      local bcol = b.range and b.range.start.character or b.col
      return acol < bcol
    end)

    local action
    local chosen
    for _, candidate in ipairs(diagnostics) do
      local key = diagnostic_key(candidate)
      if not attempted[key] then
        attempted[key] = true
        action = first_action(client, bufnr, candidate)
        if action then
          chosen = candidate
          break
        end
      end
    end

    if not action then
      break
    end

    apply_action(client, action)
    applied = applied + 1
    total_applied = total_applied + 1
    if chosen then
      for _, sibling in ipairs(diagnostics) do
        local siblingLine = sibling.range and sibling.range.start.line or sibling.lnum
        local chosenLine = chosen.range and chosen.range.start.line or chosen.lnum
        if siblingLine == chosenLine then
          attempted[diagnostic_key(sibling)] = true
        end
      end
    end
    pcall(vim.cmd, 'silent write')
    vim.wait(action_timeout)
  end

  if applied > 0 then
    changed_files = changed_files + 1
    vim.cmd('silent write')
    print(('fixed %d: %s'):format(applied, file))
  end

  if debug then
    print(('file %.0fms applied %d: %s'):format(
      (vim.uv.hrtime() - started) / 1e6,
      applied,
      file
    ))
  end
end

print(('applied %d actions across %d files (root %s)'):format(total_applied, changed_files, root))
vim.cmd('quit')
