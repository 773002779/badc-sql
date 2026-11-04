import { useEffect, useRef } from 'react';
import { basicSetup } from 'codemirror';
import { Compartment, EditorState } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { defaultKeymap, indentWithTab } from '@codemirror/commands';
import { sql, MySQL } from '@codemirror/lang-sql';
import { oneDark } from '@codemirror/theme-one-dark';
import { useTheme } from '@/theme/ThemeProvider';

interface SqlEditorProps {
  value: string;
  onChange?: (value: string) => void;
  onRun?: () => void;
  readOnly?: boolean;
  placeholder?: string;
  /** 编辑区最小高度（px） */
  minHeight?: number;
}

/**
 * SQL 编辑器：CodeMirror 6 + MySQL 高亮。
 * Ctrl/Cmd+Enter 执行；明暗主题跟随全局设置。
 */
export function SqlEditor({
  value,
  onChange,
  onRun,
  readOnly = false,
  placeholder = '-- 输入 SQL，Ctrl + Enter 执行',
  minHeight = 190,
}: SqlEditorProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const themeCompartment = useRef(new Compartment());
  const { mode } = useTheme();

  // 用 ref 保存最新回调，避免重建编辑器
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  const onRunRef = useRef(onRun);
  onRunRef.current = onRun;
  const emitRef = useRef(false);

  useEffect(() => {
    if (!hostRef.current) return;

    const state = EditorState.create({
      doc: value,
      extensions: [
        basicSetup,
        sql({ dialect: MySQL }),
        themeCompartment.current.of(mode === 'dark' ? oneDark : []),
        EditorView.lineWrapping,
        EditorState.readOnly.of(readOnly),
        EditorView.editable.of(!readOnly),
        keymap.of([
          {
            key: 'Mod-Enter',
            run: () => {
              onRunRef.current?.();
              return true;
            },
          },
          indentWithTab,
          ...defaultKeymap,
        ]),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            emitRef.current = true;
            onChangeRef.current?.(update.state.doc.toString());
          }
        }),
        EditorView.theme({
          '&': { minHeight: `${minHeight}px` },
          '.cm-content, .cm-gutter': { minHeight: `${minHeight}px` },
        }),
      ],
    });

    const view = new EditorView({ state, parent: hostRef.current });
    viewRef.current = view;
    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // 只在挂载时创建；外部 value 同步见下一个 effect
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readOnly, minHeight]);

  // 外部受控值变化时同步（避免用户输入触发回写导致光标跳动）
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    if (emitRef.current) {
      emitRef.current = false;
      return;
    }
    const current = view.state.doc.toString();
    if (current !== value) {
      view.dispatch({ changes: { from: 0, to: current.length, insert: value } });
    }
  }, [value]);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    view.dispatch({
      effects: themeCompartment.current.reconfigure(mode === 'dark' ? oneDark : []),
    });
  }, [mode]);

  return (
    <div className="sql-editor-shell">
      <div className="sql-editor-toolbar">
        <span>SQL 编辑器</span>
        <span className="muted-3">Ctrl + Enter 执行</span>
      </div>
      <div className="sql-editor-host" ref={hostRef} aria-label={placeholder} />
    </div>
  );
}
