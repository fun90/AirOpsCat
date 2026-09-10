import assert from 'node:assert/strict';
import test from 'node:test';

import {
    buildInitialTransactionPayload,
    buildOnboardingUserSource,
    createAccountFormDefaults,
    validateAccountOnboardingForm
} from '../../main/resources/META-INF/resources/static/js/person/account-form-defaults.mjs';

test('账户新增默认值完整且每次返回独立对象', () => {
    const now = new Date('2026-07-16T02:00:00.000Z');
    const first = createAccountFormDefaults(now);
    const second = createAccountFormDefaults(now);

    assert.equal(first.maxConnections, 100);
    assert.equal(first.maxIps, 2);
    assert.equal(first.bandwidth, 500);
    assert.equal(first.downloadMbps, 12);
    assert.equal(first.uploadMbps, 8);
    assert.equal(first.level, 4);
    assert.equal(first.nodeMultiple, 2);
    assert.equal(first.amount, '');
    assert.equal(first.paymentMethod, '');
    assert.equal(first.userMode, 'new');
    assert.equal(first.newUser.role, 'VIP');
    assert.equal(first.remark, '');
    assert.equal(Object.hasOwn(first.newUser, 'remarkName'), false);
    assert.equal(Object.hasOwn(first.newUser, 'remark'), false);
    assert.equal(first.fromDate, '2026-07-16T10:00');
    assert.equal(first.toDate, '2026-08-16T10:00');

    assert.equal(first.newUser.password.length, 16);
    assert.match(first.newUser.password, /^[A-Za-z0-9]{16}$/);
    assert.notEqual(first.newUser.password, second.newUser.password);

    first.newUser.email = 'changed@example.com';
    first.tagIds.push(1);
    assert.equal(second.newUser.email, '');
    assert.deepEqual(second.tagIds, []);
});

test('按用户来源只构造当前模式字段', () => {
    const item = createAccountFormDefaults(new Date('2026-07-16T02:00:00.000Z'));
    item.newUser.email = 'new@example.com';
    item.newUser.password = 'secret123';
    item.remark = '主账号';
    assert.deepEqual(buildOnboardingUserSource(item), {
        userId: null,
        newUser: {
            email: 'new@example.com',
            password: 'secret123',
            nickName: null,
            role: 'VIP',
            disabled: 0
        }
    });

    item.userMode = 'existing';
    item.userId = '42';
    assert.deepEqual(buildOnboardingUserSource(item), { userId: 42, newUser: null });
});

test('校验首期金额和付款方式并正确构造可选交易', () => {
    const item = createAccountFormDefaults(new Date('2026-07-16T02:00:00.000Z'));
    item.newUser.email = 'new@example.com';
    item.newUser.password = 'secret123';
    assert.equal(validateAccountOnboardingForm(item).remark, '请填写账号备注名');
    item.remark = '主账号';
    assert.deepEqual(validateAccountOnboardingForm(item), {});
    assert.deepEqual(buildInitialTransactionPayload(item), { amount: null, paymentMethod: null });

    item.amount = '99';
    assert.equal(validateAccountOnboardingForm(item).paymentMethod, '请选择付款方式');
    item.paymentMethod = 'WeChat';
    assert.deepEqual(validateAccountOnboardingForm(item), {});
    assert.deepEqual(buildInitialTransactionPayload(item), { amount: 99, paymentMethod: 'WeChat' });

    item.amount = '0';
    assert.equal(validateAccountOnboardingForm(item).amount, '请输入有效金额');
});
