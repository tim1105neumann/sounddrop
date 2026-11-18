// Webpack loader for skiko.mjs, working around WebKit bug 329421 (Safari 27.2, also CMP-10986).
//
// skiko.mjs wraps each Skia export as `(...a) => (X = loadedWasm._["X"])(...a)`, a JavaScript tail call into
// WebAssembly. Safari 27.2 passes some float arguments of such calls as NaN once the caller is optimised, so Skia
// measures garbage and nothing is drawn: the page stays white. A call inside `try` is never a tail call.
'use strict';

// Matches the wrapper as skiko ships it and in minified form, e.g. `(...e)=>(y=m._.X)(...e)`.
const TAIL_CALL_WRAPPER =
    /\(\.\.\.([\w$]+)\)\s*=>\s*\(\s*([\w$]+)\s*=\s*([\w$]+)\._(\.[\w$]+|\[(["'])[\w$]+\5\])\s*\)\s*\(\s*\.\.\.\1\s*\)/g;

function findTailCallWrappers(code) {
    return Array.from(code.matchAll(TAIL_CALL_WRAPPER), (match) => match[0]);
}

function skikoNoTailCallLoader(source) {
    let count = 0;
    const code = source.replace(TAIL_CALL_WRAPPER, (match, args, binding, holder, member) => {
        count++;
        return `(...${args}) => { try { return (${binding} = ${holder}._${member})(...${args}) } catch (e) { throw e } }`;
    });
    // Fail loudly if a skiko update changes the wrapper shape, instead of silently shipping a white page.
    if (count === 0) throw new Error(`${this.resourcePath}: no skiko export wrapper matched`);
    return code;
}

module.exports = skikoNoTailCallLoader;
module.exports.findTailCallWrappers = findTailCallWrappers;
