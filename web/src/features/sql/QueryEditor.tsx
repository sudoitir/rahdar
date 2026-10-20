import { useEffect, useId, useRef } from 'react';
import { EditorState, Prec, type Extension } from '@codemirror/state';
import { EditorView, keymap, placeholder } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { syntaxHighlighting } from '@codemirror/language';
import {
  autocompletion,
  closeBrackets,
  closeBracketsKeymap,
  completionKeymap,
  type CompletionContext,
  type CompletionResult,
} from '@codemirror/autocomplete';
import { sql } from '@codemirror/lang-sql';
import { lintKeymap, setDiagnostics, type Diagnostic } from '@codemirror/lint';
import { Text } from '@mantine/core';

import { codeHighlight as highlight, codeTheme as theme } from '../../ui/codeMirrorTheme.ts';

import { COLUMNS, EVALUATION_WORDS, FUNCTIONS } from './catalogue.ts';
import classes from './QueryEditor.module.css';

/**
 * The completion source. Written by hand rather than handed to `lang-sql`'s
 * `schema` option because a subscription name is not a SQL identifier: `ORDER.IN` is a
 * reserved word and a dot, so it is always double-quoted, and the schema
 * completer has no way to produce that.
 */
function completions(subscriptions: string[]) {
  const columnOptions = COLUMNS.map((c) => ({
    label: c.name,
    type: 'property',
    detail: EVALUATION_WORDS[c.evaluation],
    info: c.indexOnly ? `${c.description} Index only.` : c.description,
  }));
  const functionOptions = FUNCTIONS.map((f) => ({
    label: `${f}(`,
    type: 'function',
    detail: 'function',
  }));
  const sourceOptions = [
    { label: 'broker.', type: 'namespace', detail: 'read the live brokers' },
    { label: 'index.', type: 'namespace', detail: 'read the historical index' },
  ];

  return (context: CompletionContext): CompletionResult | null => {
    // After FROM, the only useful completion is a subscription name — quoted, because
    // that is the only spelling the parser accepts.
    const from = context.matchBefore(/from\s+(?:broker\.|index\.)?"?[\w.*#$-]*/i);
    if (from) {
      const openQuote = from.text.lastIndexOf('"');
      const start = openQuote >= 0 ? from.from + openQuote : from.to - wordAfterFrom(from.text).length;
      return {
        from: start,
        options: [
          ...(openQuote >= 0 ? [] : sourceOptions),
          ...subscriptions.map((q) => ({
            label: `"${q}"`,
            type: 'class',
            detail: 'subscription',
          })),
        ],
        validFor: /^["\w.*#$-]*$/,
      };
    }

    const word = context.matchBefore(/[\w.]*/);
    if (!word || (word.from === word.to && !context.explicit)) return null;
    return {
      from: word.from,
      options: [
        ...columnOptions,
        ...functionOptions,
        {
          label: 'props.',
          type: 'property',
          detail: 'an application property, by name',
        },
      ],
      validFor: /^[\w.]*$/,
    };
  };
}

/**
 * Where the offending token is in the query, as a lint diagnostic.
 *
 * <p>The plan strip already names the token a rejection is about. Naming it and leaving the operator
 * to find it in their own query is half an answer; on a long query it is the half that costs the
 * time. The server reports the token, not its position, so it is found here, case-insensitively
 * because the server reports it as the parser saw it. A token no longer in the text marks nothing.
 */
function diagnosticsFor(state: EditorState, error: QueryError | null): Diagnostic[] {
  if (!error?.token) return [];
  const at = state.doc.toString().toLowerCase().indexOf(error.token.toLowerCase());
  if (at < 0) return [];
  return [{ from: at, to: at + error.token.length, severity: 'error', message: error.message }];
}

/** The token after `FROM `, so the completion replaces it rather than appending to it. */
function wordAfterFrom(text: string): string {
  return /from\s+(\S.*)?$/i.exec(text)?.[1] ?? '';
}

/** What is wrong with the query: the offending token, and what to say about it. */
export interface QueryError {
  token: string;
  message: string;
}

/**
 * The console's query editor: CodeMirror 6 with the SQL grammar, completion over
 * the column catalogue and the cluster's live subscription names, ⌘/Ctrl-Enter to run and
 * ⌘/Ctrl-. to cancel. A rejected query is marked on its offending token as a lint
 * diagnostic, which F8 steps to.
 *
 * <p>Deliberately not `basicSetup` — line numbers, folding and a gutter belong to
 * a file, not to a four-line query. Everything here earns its place.
 */
export function QueryEditor({
  value,
  onChange,
  onRun,
  onCancel,
  onMaximise,
  onEscape,
  subscriptions,
  label = 'Query',
  describedBy,
  error = null,
}: Readonly<{
  value: string;
  onChange: (next: string) => void;
  onRun: () => void;
  /** Mod+. */
  onCancel: () => void;
  /** Mod+Shift+M: give the results the whole workspace, or take it back. */
  onMaximise: () => void;
  /** Escape with nothing left to close or collapse: the caller moves focus out of the editor. */
  onEscape: () => void;
  /** Subscription names for completion, from the cluster's current snapshot. */
  subscriptions: string[];
  label?: string;
  /** The element that describes the query, such as its cost verdict. */
  describedBy?: string;
  /** What is wrong with the query, marked on the offending token. Null clears the mark. */
  error?: QueryError | null;
}>) {
  const labelId = useId();
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);

  // The extensions are built once, so the keymap and completion source close over
  // the first render's callbacks. These refs keep them current without tearing
  // the editor down and losing the cursor on every parent render.
  const latest = useRef({ value, onChange, onRun, onCancel, onMaximise, onEscape, subscriptions });
  latest.current = { value, onChange, onRun, onCancel, onMaximise, onEscape, subscriptions };

  useEffect(() => {
    if (!host.current) return;

    const extensions: Extension[] = [
      history(),
      // Above the default keymap: Enter alone inserts a newline, and Mod-Enter
      // must not be swallowed by anything that binds it first.
      Prec.high(
        keymap.of([
          {
            key: 'Mod-Enter',
            preventDefault: true,
            run: () => {
              latest.current.onRun();
              return true;
            },
          },
        ]),
      ),
      Prec.high(
        keymap.of([
          {
            // Cancelling is a key of its own, never Escape: Escape is how an operator leaves the
            // editor, and leaving it must never stop a query that is reading brokers.
            key: 'Mod-.',
            preventDefault: true,
            run: () => {
              latest.current.onCancel();
              return true;
            },
          },
        ]),
      ),
      Prec.high(
        keymap.of([
          {
            // The same key as the page's, so it means one thing wherever focus is. It takes the lint
            // keymap's Mod-Shift-m (the diagnostics list): the error is already marked in the editor
            // and stated in words under it.
            key: 'Mod-Shift-m',
            preventDefault: true,
            run: () => {
              latest.current.onMaximise();
              return true;
            },
          },
        ]),
      ),
      // In this order Escape closes the completion list, then collapses the selection to one
      // cursor, and only then, with nothing left to close, hands focus on.
      keymap.of([
        ...closeBracketsKeymap,
        ...completionKeymap,
        ...defaultKeymap,
        ...historyKeymap,
        // F8 steps through the diagnostics.
        ...lintKeymap,
        {
          key: 'Escape',
          run: () => {
            latest.current.onEscape();
            return true;
          },
        },
      ]),
      closeBrackets(),
      sql({ upperCaseKeywords: true }),
      syntaxHighlighting(highlight),
      autocompletion({
        override: [(c) => completions(latest.current.subscriptions)(c)],
      }),
      placeholder('SELECT * FROM "ORDER.IN" WHERE priority > 4 LIMIT 100'),
      EditorView.lineWrapping,
      // The editor's content is a contenteditable div, not a form control, so a
      // <label for> cannot reach it. The visible label below is its accessible
      // name through aria-labelledby — a placeholder is not a label.
      EditorView.contentAttributes.of({
        'aria-labelledby': labelId,
        ...(describedBy ? { 'aria-describedby': describedBy } : {}),
        role: 'textbox',
        'aria-multiline': 'true',
        // The editor scrolls inside itself when the query is long, and a scroll area must be reachable by
        // keyboard: axe does not count a contenteditable as focusable, an explicit tab stop it does.
        tabindex: '0',
      }),
      theme,
      EditorView.updateListener.of((update) => {
        if (update.docChanged) latest.current.onChange(update.state.doc.toString());
      }),
    ];

    const editor = new EditorView({
      state: EditorState.create({ doc: latest.current.value, extensions }),
      parent: host.current,
    });
    view.current = editor;
    return () => {
      editor.destroy();
      view.current = null;
    };
    // Built once for the editor's lifetime: it starts from the value at that moment, and later changes
    // are synced by the effect below.
  }, [labelId, describedBy]);

  // An external change to the query — a loaded example, a restored URL — is
  // pushed in. A change the editor itself made is already in the document, and
  // replacing it would move the cursor to the end mid-typing.
  useEffect(() => {
    const editor = view.current;
    if (!editor) return;
    const current = editor.state.doc.toString();
    if (current === value) return;
    editor.dispatch({
      changes: { from: 0, to: current.length, insert: value },
    });
  }, [value]);

  useEffect(() => {
    const editor = view.current;
    if (editor) editor.dispatch(setDiagnostics(editor.state, diagnosticsFor(editor.state, error)));
  }, [error, value]);

  return (
    <div className={classes.editor}>
      <Text id={labelId} component="label" size="xs" fw={600} c="dimmed" display="block" mb={4}>
        {label}
      </Text>
      <div ref={host} className={classes.host} />
    </div>
  );
}
