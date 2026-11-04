/**
 * 把一段 SQL 脚本按分号拆成单条语句。
 *
 * 后端要求"一条语句一个元素"（多语句拼接会被 BLOCK），所以拆分必须发生在提交之前。
 * 这里只处理最常见的词法边界：单/双引号、反引号、转义反斜杠、行注释、块注释。
 * 存储过程里的 DELIMITER 不在本期范围（运维临时执行也只收单条语句）。
 */
export function splitSqlStatements(script: string): string[] {
  const statements: string[] = [];
  let current = '';
  let quote: '"' | "'" | '`' | null = null;
  let inLineComment = false;
  let inBlockComment = false;

  for (let i = 0; i < script.length; i += 1) {
    const char = script[i];
    const next = script[i + 1];

    if (inLineComment) {
      current += char;
      if (char === '\n') inLineComment = false;
      continue;
    }

    if (inBlockComment) {
      current += char;
      if (char === '*' && next === '/') {
        current += next;
        i += 1;
        inBlockComment = false;
      }
      continue;
    }

    if (quote) {
      current += char;
      if (char === '\\' && quote !== '`') {
        // 反斜杠转义：连同下一个字符一起吞掉，避免 \' 被当成闭合
        if (next !== undefined) {
          current += next;
          i += 1;
        }
        continue;
      }
      if (char === quote) {
        // SQL 里 '' / "" 是转义后的同一个引号
        if (next === quote) {
          current += next;
          i += 1;
          continue;
        }
        quote = null;
      }
      continue;
    }

    if (char === '-' && next === '-') {
      current += char + next;
      i += 1;
      inLineComment = true;
      continue;
    }

    if (char === '#') {
      current += char;
      inLineComment = true;
      continue;
    }

    if (char === '/' && next === '*') {
      current += char + next;
      i += 1;
      inBlockComment = true;
      continue;
    }

    if (char === '"' || char === "'" || char === '`') {
      quote = char;
      current += char;
      continue;
    }

    if (char === ';') {
      const trimmed = current.trim();
      if (trimmed) statements.push(trimmed);
      current = '';
      continue;
    }

    current += char;
  }

  const tail = current.trim();
  if (tail) statements.push(tail);
  return statements;
}

/** 把脚本拆成非空语句后，统计有多少条（用于界面提示）。 */
export function countStatements(script: string): number {
  return splitSqlStatements(script).length;
}

/** 粗判是否包含破坏性 DDL：只用于界面提示，最终判定在后端。 */
export function looksDestructive(script: string): boolean {
  return /\b(drop\s+(table|database|schema)|truncate|rename\s+table)\b/i.test(script);
}
