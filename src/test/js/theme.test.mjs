import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { runInNewContext } from 'node:vm';

const source = readFileSync(new URL('../../main/resources/META-INF/resources/static/js/common/theme.js', import.meta.url), 'utf8');

for (const dark of [false, true]) {
    test(`系统${dark ? '暗' : '亮'}色模式在脚本执行时立即生效，并实时跟随变化`, () => {
        const root = new Map();
        const listeners = new Map();
        const media = {
            matches: dark,
            addEventListener: (event, listener) => listeners.set(event, listener)
        };
        const window = {
            matchMedia(query) {
                assert.equal(query, '(prefers-color-scheme: dark)');
                return media;
            },
            get localStorage() {
                throw new Error('主题不应依赖本地存储权限或旧 demo 设置');
            },
            location: { search: `?theme=${dark ? 'light' : 'dark'}&theme-primary=red` }
        };
        runInNewContext(source, {
            window,
            document: { documentElement: { setAttribute: (key, value) => root.set(key, value) } }
        });

        assert.equal(root.get('data-bs-theme'), dark ? 'dark' : 'light');
        media.matches = !dark;
        listeners.get('change')();
        assert.equal(root.get('data-bs-theme'), dark ? 'light' : 'dark');
        media.matches = dark;
        listeners.get('change')();
        assert.equal(root.get('data-bs-theme'), dark ? 'dark' : 'light');
        assert.equal(root.size, 1);
    });
}
