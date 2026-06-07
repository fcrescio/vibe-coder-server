/*
 * v1.70.0 — Console stream-json event → user-friendly message renderer.
 *
 * Extracted from WebProjectTemplates.kt inline script so that main project
 * console, scratch chat, and sub-agent consoles all share the same logic.
 * Pure functions only; DOM/state is managed by each console's inline script.
 *
 * Exposes: window.VibeConsole = { clip, summarizeInput, renderToolUse,
 *   extractToolResult, renderUnknown, renderMarkdown, tryParseJson,
 *   extractToolImage, renderImageToolResult, renderUserPrompt }
 *
 * Must be loaded (synchronously) before any console inline <script>.
 */
(function () {
  'use strict';

  function clip(s, n) {
    s = String(s == null ? '' : s);
    return s.length > n ? s.slice(0, n) + ' …(+' + (s.length - n) + ')' : s;
  }

  // tool input object → short "key=value" summary (avoid raw JSON dump).
  function summarizeInput(i) {
    if (i == null) return '';
    if (typeof i === 'string') return clip(i, 300);
    if (Array.isArray(i)) return '[' + i.length + ' item(s)]';
    if (typeof i !== 'object') return String(i);
    var keys = Object.keys(i);
    if (keys.length === 0) return '';
    var parts = [];
    for (var k = 0; k < keys.length && parts.length < 6; k++) {
      var key = keys[k], v = i[key], vs;
      if (v == null) vs = '';
      else if (typeof v === 'string') vs = clip(v, 80);
      else if (Array.isArray(v)) vs = '[' + v.length + ']';
      else if (typeof v === 'object') {
        var ks = Object.keys(v);
        vs = ks.length ? '{' + ks.slice(0, 3).join(', ') + (ks.length > 3 ? ', …' : '') + '}' : '{}';
      }
      else vs = String(v);
      parts.push(key + '=' + vs);
    }
    return parts.join('  ');
  }

  // tool_use(name, input) → { label, body } one-line friendly representation.
  function renderToolUse(name, input) {
    var i = input || {};
    if (typeof i === 'string') { try { i = JSON.parse(i); } catch (e) { return { label: name || 'tool', body: clip(input, 500) }; } }
    switch (name) {
      case 'Bash': {
        var cmd = i.command || '';
        var desc = i.description ? ' — ' + i.description : '';
        return { label: '$', body: clip(cmd, 400) + desc };
      }
      case 'Read': {
        var p = i.file_path || i.path || '';
        var range = (i.offset != null || i.limit != null)
          ? ' [' + (i.offset || 0) + ', +' + (i.limit || '?') + ']' : '';
        return { label: '📄 Read', body: p + range };
      }
      case 'Write': {
        var p2 = i.file_path || i.path || '';
        var sz = (i.content || '').length;
        return { label: '✏️ Write', body: p2 + ' (' + sz + ' chars)' };
      }
      case 'Edit': {
        var p3 = i.file_path || i.path || '';
        var oldS = clip(i.old_string || '', 80);
        var newS = clip(i.new_string || '', 80);
        var ra = i.replace_all ? ' [all]' : '';
        return { label: '✎ Edit' + ra, body: p3 + '\n  - ' + oldS + '\n  + ' + newS };
      }
      case 'Glob':
        return { label: '🔍 Glob', body: (i.pattern || '') + (i.path ? ' in ' + i.path : '') };
      case 'Grep':
        return { label: '🔎 Grep', body: '"' + clip(i.pattern || '', 80) + '"' +
          (i.path ? ' in ' + i.path : '') + (i.glob ? ' (' + i.glob + ')' : '') };
      case 'MultiEdit': {
        var pm = i.file_path || i.path || '';
        var ne = (i.edits || []).length;
        return { label: '✎ MultiEdit', body: pm + ' (' + ne + ' edit(s))' };
      }
      case 'NotebookEdit':
        return { label: '✎ Notebook', body: (i.notebook_path || '') + (i.cell_type ? ' [' + i.cell_type + ']' : '') };
      case 'TaskCreate':
        return { label: '📋 TaskCreate', body: i.subject || i.description || '' };
      case 'TaskUpdate':
        return { label: '📋 TaskUpdate',
          body: 'id=' + (i.taskId || '?') + (i.status ? ' status=' + i.status : '') +
                (i.subject ? ' "' + i.subject + '"' : '') };
      case 'TaskList':
        return { label: '📋 TaskList', body: i.status ? 'status=' + i.status : '' };
      case 'TaskGet':
        return { label: '📋 TaskGet', body: 'id=' + (i.taskId || '?') };
      case 'TaskOutput':
        return { label: '📋 TaskOutput', body: 'id=' + (i.taskId || '?') };
      case 'TaskStop':
        return { label: '📋 TaskStop', body: 'id=' + (i.taskId || '?') };
      case 'TodoWrite': {
        var n = (i.todos || []).length;
        return { label: '📋 TodoWrite', body: n + ' todo(s)' };
      }
      case 'Task':
        return { label: '🤖 Task' + (i.subagent_type ? '·' + i.subagent_type : ''),
                 body: clip(i.description || i.prompt || '', 300) };
      case 'Agent':
        return { label: '🤖 Agent' + (i.subagent_type ? '·' + i.subagent_type : ''),
                 body: clip(i.description || i.prompt || '', 300) };
      case 'WebSearch':
        return { label: '🌐 WebSearch', body: '"' + clip(i.query || '', 200) + '"' };
      case 'WebFetch':
        return { label: '🌐 WebFetch', body: i.url || '' };
      case 'ToolSearch':
        return { label: '🔧 ToolSearch', body: clip(i.query || '', 200) };
      case 'Monitor':
        return { label: '⏱ Monitor', body: clip(i.command || i.description || summarizeInput(i), 200) };
      case 'ScheduleWakeup':
        return { label: '⏰ Schedule',
                 body: (i.delaySeconds != null ? '+' + i.delaySeconds + 's  ' : '') + clip(i.reason || '', 200) };
      case 'BashOutput':
        return { label: '$ output', body: 'bash_id=' + (i.bash_id || i.shell_id || '?') };
      case 'KillShell':
      case 'KillBash':
        return { label: '✖ kill shell', body: 'id=' + (i.shell_id || i.bash_id || '?') };
      case 'SlashCommand':
        return { label: '/ command', body: clip(i.command || '', 200) };
      case 'ExitPlanMode':
      case 'EnterPlanMode':
        return { label: '📝 ' + name, body: clip(i.plan || '', 300) };
      case 'AskUserQuestion': {
        var q = (i.questions && i.questions[0] && i.questions[0].question) || '';
        return { label: '❓ Question', body: clip(q, 200) };
      }
      case 'PushNotification':
        return { label: '🔔 Notify', body: clip(i.message || i.title || '', 200) };
      case 'Skill':
        return { label: '🧩 Skill', body: (i.skill || '') + (i.args ? ' ' + clip(i.args, 120) : '') };
      case 'Workflow':
        return { label: '🔀 Workflow', body: clip(i.description || i.name || '', 200) };
      default: {
        // MCP tools: mcp__<server>__<tool> → "🔌 server·tool"
        if (name && name.indexOf('mcp__') === 0) {
          var parts = name.split('__');
          var server = parts[1] || '';
          var tool = parts.slice(2).join('__');
          return { label: '🔌 ' + server + (tool ? '·' + tool : ''), body: summarizeInput(i) };
        }
        return { label: name || 'tool', body: summarizeInput(i) };
      }
    }
  }

  // Unescape a JSON-encoded string that arrived double-encoded.
  function unescapeJsonString(s) {
    if (typeof s !== 'string' || s.charAt(0) !== '"') return s;
    if (s.length >= 2 && s.charAt(s.length - 1) === '"') {
      try { var v = JSON.parse(s); if (typeof v === 'string') return v; } catch (e) {}
    }
    if (/\\[ntr"\\]/.test(s)) {
      var t = s.slice(1);
      if (t.charAt(t.length - 1) === '"') t = t.slice(0, -1);
      return t.replace(/\\n/g, '\n').replace(/\\t/g, '\t').replace(/\\r/g, '\r')
              .replace(/\\"/g, '"').replace(/\\\\/g, '\\');
    }
    return s;
  }

  // tool_result content → human-readable text.
  function extractToolResult(output) {
    if (output == null) return '';
    if (typeof output === 'string') return unescapeJsonString(output);
    if (Array.isArray(output)) {
      var parts = [];
      for (var j = 0; j < output.length; j++) {
        var b = output[j];
        if (b == null) continue;
        if (typeof b === 'string') { parts.push(b); continue; }
        if (b.type === 'text' && typeof b.text === 'string') parts.push(b.text);
        else if (b.type === 'image') parts.push('[image]');
        else if (typeof b.text === 'string') parts.push(b.text);
        else parts.push(summarizeInput(b));
      }
      return parts.join('\n');
    }
    if (typeof output === 'object') {
      if (typeof output.text === 'string') return output.text;
      if (typeof output.content === 'string') return output.content;
      return summarizeInput(output);
    }
    return String(output);
  }

  function fmtEpoch(sec) {
    var n = Number(sec);
    if (!isFinite(n)) return String(sec);
    try { return new Date(n * 1000).toLocaleString(); } catch (e) { return String(sec); }
  }

  // console_unknown.raw → { cls, label, body, cat } or null (hidden).
  function renderUnknown(raw) {
    var o = raw;
    if (typeof o === 'string') {
      try { o = JSON.parse(o); }
      catch (e) {
        var mt = String(o).match(/"type"\s*:\s*"([\w-]+)"/);
        var tn = mt ? mt[1] : null;
        if (tn === 'thinking') return { cls: 'thinking', label: '💭 Thinking…', body: '💭 Thinking…', cat: 'thinking' };
        if (tn) return { cls: 'thinking', label: tn, body: '· ' + tn + ' ·', cat: 'thinking' };
        return { cls: 'thinking', label: 'event', body: '· event ·', cat: 'thinking' };
      }
    }
    if (o == null || typeof o !== 'object') return null;
    var type = o.type;

    if (type === 'thinking') {
      var th = String(o.thinking || '').trim();
      if (!th) return { cls: 'thinking', label: '💭 Thinking…', body: '💭 Thinking…', cat: 'thinking' };
      return { cls: 'thinking', label: '💭 thinking', body: clip(th, 4000), cat: 'thinking' };
    }

    if (type === 'user') return null;

    if (type === 'system') {
      var st = o.subtype;
      if (st === 'init' || st === 'thinking_tokens') return null;
      if (st === 'task_started')
        return { cls: 'tool', label: '🟢 task', body: clip(o.description || '', 200) + (o.task_type ? ' [' + o.task_type + ']' : ''), cat: 'todo' };
      if (st === 'task_notification') {
        var done = o.status === 'completed';
        return { cls: done ? 'tool-out' : 'tool', label: done ? '✓ task' : 'task·' + (o.status || ''),
                 body: clip(o.summary || o.output_file || '', 300), cat: 'todo' };
      }
      if (st === 'task_updated' || st === 'task_progress')
        return { cls: 'tool', label: '… task', body: clip(o.summary || o.description || ('status=' + (o.status || '')), 200), cat: 'todo' };
      return { cls: 'sys', label: 'system·' + (st || '?'), body: summarizeInput(o), cat: 'system' };
    }

    if (type === 'rate_limit_event') {
      var info = o.rate_limit_info || {};
      var body = (info.status || '') +
                 (info.rateLimitType ? ' · ' + info.rateLimitType : '') +
                 (info.resetsAt ? ' · resets ' + fmtEpoch(info.resetsAt) : '');
      return { cls: 'sys', label: '⏳ rate limit', body: body || summarizeInput(info), cat: 'system' };
    }

    return { cls: 'sys', label: type || 'event', body: summarizeInput(o), cat: 'system' };
  }

  // Lightweight markdown → safe HTML. No external dependencies.
  function mdEsc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function renderMarkdown(src) {
    src = String(src == null ? '' : src);
    var blocks = [];
    src = src.replace(/```([a-zA-Z0-9_+#.-]*)[ \t]*\n?([\s\S]*?)```/g, function (_, lang, code) {
      var idx = blocks.length;
      blocks.push({ lang: (lang || '').trim(), code: code.replace(/\n$/, '') });
      return '\uE000CB' + idx + '\uE000';
    });
    var h = mdEsc(src);
    var inlines = [];
    h = h.replace(/`([^`\n]+)`/g, function (_, c) {
      var idx = inlines.length; inlines.push(c); return '\uE000IC' + idx + '\uE000';
    });
    // tables
    h = h.replace(
      /(?:^|\n)([ \t]*\|.+\|[ \t]*)\n[ \t]*\|[ \t:|-]+\|[ \t]*\n((?:[ \t]*\|.+\|[ \t]*(?:\n|$))*)/g,
      function (_, header, body) {
        var cells = function (row) {
          return row.trim().replace(/^\||\|$/g, '').split('|').map(function (c) { return c.trim(); });
        };
        var ths = cells(header).map(function (c) { return '<th>' + c + '</th>'; }).join('');
        var trs = body.replace(/\n+$/, '').split('\n')
          .filter(function (r) { return r.trim(); })
          .map(function (row) {
            return '<tr>' + cells(row).map(function (c) { return '<td>' + c + '</td>'; }).join('') + '</tr>';
          }).join('');
        return '\n<table class="md-table"><thead><tr>' + ths + '</tr></thead><tbody>' + trs + '</tbody></table>\n';
      });
    // headings
    h = h.replace(/^\s*######\s+(.+)$/gm, '<h6>$1</h6>')
         .replace(/^\s*#####\s+(.+)$/gm, '<h5>$1</h5>')
         .replace(/^\s*####\s+(.+)$/gm, '<h4>$1</h4>')
         .replace(/^\s*###\s+(.+)$/gm, '<h3>$1</h3>')
         .replace(/^\s*##\s+(.+)$/gm, '<h2>$1</h2>')
         .replace(/^\s*#\s+(.+)$/gm, '<h1>$1</h1>');
    // horizontal rule
    h = h.replace(/^\s*(?:---|\*\*\*|___)\s*$/gm, '<hr>');
    // blockquote
    h = h.replace(/^\s*&gt;\s?(.*)$/gm, '<blockquote>$1</blockquote>');
    // unordered list
    h = h.replace(/(?:^|\n)((?:[ \t]*[-*+]\s+.*(?:\n|$))+)/g, function (_, blk) {
      var items = blk.replace(/\n+$/, '').split(/\n/).map(function (li) {
        return '<li>' + li.replace(/^[ \t]*[-*+]\s+/, '') + '</li>';
      }).join('');
      return '\n<ul>' + items + '</ul>\n';
    });
    // ordered list
    h = h.replace(/(?:^|\n)((?:[ \t]*\d+\.\s+.*(?:\n|$))+)/g, function (_, blk) {
      var items = blk.replace(/\n+$/, '').split(/\n/).map(function (li) {
        return '<li>' + li.replace(/^[ \t]*\d+\.\s+/, '') + '</li>';
      }).join('');
      return '\n<ol>' + items + '</ol>\n';
    });
    // bold / italic / strikethrough
    h = h.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
         .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
         .replace(/~~([^~]+)~~/g, '<del>$1</del>');
    // links
    h = h.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)"'<>]+)\)/g,
      '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
    // paragraphs / line breaks
    h = h.replace(/\n{2,}/g, '\n\n');
    h = h.split('\n').map(function (line) { return line; }).join('\n');
    h = h.replace(/\n/g, '<br>');
    // clean excess <br> around block tags
    h = h.replace(/<br>\s*(<\/?(?:h[1-6]|ul|ol|li|blockquote|hr|pre|table|thead|tbody|tr|th|td)[^>]*>)/g, '$1')
         .replace(/(<\/?(?:h[1-6]|ul|ol|li|blockquote|hr|pre|table|thead|tbody|tr|th|td)[^>]*>)\s*<br>/g, '$1');
    // inline code restore
    h = h.replace(/\uE000IC(\d+)\uE000/g, function (_, i) {
      return '<code class="md-code">' + mdEsc(inlines[i]) + '</code>';
    });
    // code block restore
    h = h.replace(/\uE000CB(\d+)\uE000/g, function (_, i) {
      var b = blocks[i];
      var cls = b.lang ? ' class="language-' + mdEsc(b.lang) + '"' : '';
      return '<pre class="md-pre"><code' + cls + '>' + mdEsc(b.code) + '</code></pre>';
    });
    return h;
  }

  // ── Fork-specific: image handling ──────────────────────────────────

  function tryParseJson(value) {
    if (typeof value !== 'string') return value;
    try { return JSON.parse(value); } catch (e) { return value; }
  }

  function extractToolImage(output) {
    var o = tryParseJson(output);
    if (o && typeof o === 'object' && typeof o.raw_output === 'string') {
      o = tryParseJson(o.raw_output);
    } else if (o && typeof o === 'object' && typeof o.rawOutput === 'string') {
      o = tryParseJson(o.rawOutput);
    }
    if (!o || typeof o !== 'object') return null;
    var data = o.data || o.imageData || o.base64 || null;
    var mime = o.mimeType || o.mime_type || 'image/png';
    if (!data || typeof data !== 'string' || String(mime).indexOf('image/') !== 0) return null;
    return {
      src: 'data:' + mime + ';base64,' + data,
      mime: mime,
      serial: o.serial || '',
      text: o.answer || o.analysis || o.message || '',
      copy: o.answer || o.analysis || o.message || ('image ' + mime)
    };
  }

  function renderImageToolResult(image, isError, opts) {
    var meta = [];
    if (image.serial) meta.push('device ' + image.serial);
    meta.push(image.mime);
    var html =
      (image.text ? '<div style="white-space:pre-wrap;margin-bottom:8px">' + escHtml(image.text) + '</div>' : '') +
      '<div class="console-image-result">' +
        '<img src="' + image.src + '" alt="' + escHtml(meta.join(' · ')) + '" loading="lazy">' +
      '</div>' +
      '<div class="dim" style="font-size:11px;margin-top:6px">' + escHtml(meta.join(' · ')) + '</div>';
    return { cls: isError ? 'tool-err' : 'tool-out', label: isError ? 'tool-err' : 'image result', html: html, copy: image.copy, cat: 'tool_result' };
  }

  function renderUserPrompt(text, images) {
    images = images || [];
    if (!images.length) {
      return { cls: 'user', label: 'user', body: text, cat: 'assistant' };
    }
    var html = '<div style="white-space:pre-wrap;margin-bottom:8px">' + escHtml(text) + '</div>';
    for (var i = 0; i < images.length; i++) {
      var img = images[i];
      html += '<div class="console-image-result" style="margin-top:8px">' +
        '<img src="data:' + escHtml(img.mimeType || 'image/png') + ';base64,' + escHtml(img.data || '') + '" alt="Attached image" loading="lazy">' +
        '</div>';
    }
    return { cls: 'user', label: 'user', html: html, copy: text, cat: 'assistant' };
  }

  // Expose public API
  window.VibeConsole = {
    clip: clip,
    summarizeInput: summarizeInput,
    renderToolUse: renderToolUse,
    extractToolResult: extractToolResult,
    renderUnknown: renderUnknown,
    renderMarkdown: renderMarkdown,
    tryParseJson: tryParseJson,
    extractToolImage: extractToolImage,
    renderImageToolResult: renderImageToolResult,
    renderUserPrompt: renderUserPrompt,
  };
})();
