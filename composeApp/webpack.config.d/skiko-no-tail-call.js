// Safari 27.2 workaround: keep skiko's calls into WebAssembly out of tail position.
// See composeApp/webpack/skiko-no-tail-call.cjs. Webpack runs from build/wasm/packages/<package>.
{
    const loaderPath = require('path').resolve(__dirname, '../../../../composeApp/webpack/skiko-no-tail-call.cjs');
    const { findTailCallWrappers } = require(loaderPath);

    config.module.rules.push({ test: /[\\/]skiko\.mjs$/, loader: loaderPath });

    // Minification must not fold a rewritten wrapper back into a tail call; fail the build if it does.
    config.plugins.push({
        apply(compiler) {
            const { Compilation, WebpackError } = compiler.webpack;
            compiler.hooks.thisCompilation.tap('SkikoNoTailCallCheck', (compilation) => {
                compilation.hooks.processAssets.tap(
                    { name: 'SkikoNoTailCallCheck', stage: Compilation.PROCESS_ASSETS_STAGE_ANALYSE },
                    (assets) => {
                        for (const [name, asset] of Object.entries(assets)) {
                            if (!/\.m?js$/.test(name)) continue;
                            const left = findTailCallWrappers(asset.source().toString());
                            if (left.length > 0) {
                                compilation.errors.push(new WebpackError(`${name}: ${left.length} skiko tail calls left`));
                            }
                        }
                    },
                );
            });
        },
    });
}
