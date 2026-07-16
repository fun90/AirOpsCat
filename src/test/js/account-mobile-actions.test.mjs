import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const tableTemplate = await readFile(
    new URL('../../main/resources/templates/person/account/table.html', import.meta.url),
    'utf8'
);
const modalTemplate = await readFile(
    new URL('../../main/resources/templates/person/account/modals.html', import.meta.url),
    'utf8'
);
const accountScript = await readFile(
    new URL('../../main/resources/META-INF/resources/static/js/person/account.js', import.meta.url),
    'utf8'
);

test('移动端操作入口使用表格外独立面板', () => {
    assert.match(tableTemplate, /d-none d-md-block/);
    assert.match(tableTemplate, /d-md-none[^>]+openMobileActions\(account\)/);
    assert.doesNotMatch(tableTemplate, /account-mobileActionsModal/);
    assert.match(modalTemplate, /id="account-mobileActionsModal"/);
});

test('移动端面板复用完整账户操作', () => {
    for (const action of ['edit', 'config', 'renew', 'reset-auth', 'deploy', 'disable', 'enable', 'delete']) {
        assert.match(modalTemplate, new RegExp(`performMobileAction\\('${action}'\\)`));
        assert.match(accountScript, new RegExp(`case '${action}':`));
    }
    assert.match(accountScript, /hidden\.bs\.modal/);
    assert.match(accountScript, /\{ once: true \}/);
});
