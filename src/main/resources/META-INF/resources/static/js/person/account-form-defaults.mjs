const PASSWORD_CHARSET = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
const PASSWORD_LENGTH = 16;

/**
 * 生成随机密码，优先使用加密安全随机源，缺失时退回 Math.random
 */
export function generateRandomPassword(length = PASSWORD_LENGTH) {
    const values = new Uint32Array(length);
    const cryptoObj = globalThis.crypto;
    if (cryptoObj && typeof cryptoObj.getRandomValues === 'function') {
        cryptoObj.getRandomValues(values);
    } else {
        for (let i = 0; i < length; i++) {
            values[i] = Math.floor(Math.random() * 4294967296);
        }
    }

    let result = '';
    for (let i = 0; i < length; i++) {
        result += PASSWORD_CHARSET.charAt(values[i] % PASSWORD_CHARSET.length);
    }
    return result;
}

function formatBeijingDateTimeForLocal(date) {
    if (!date) return '';

    const beijingOffset = 8 * 60;
    const utc = date.getTime() + date.getTimezoneOffset() * 60000;
    const beijingTime = new Date(utc + beijingOffset * 60000);
    const year = beijingTime.getFullYear();
    const month = String(beijingTime.getMonth() + 1).padStart(2, '0');
    const day = String(beijingTime.getDate()).padStart(2, '0');
    const hours = String(beijingTime.getHours()).padStart(2, '0');
    const minutes = String(beijingTime.getMinutes()).padStart(2, '0');
    return `${year}-${month}-${day}T${hours}:${minutes}`;
}

export function createAccountFormDefaults(now = new Date()) {
    const fromDate = new Date(now);
    const toDate = new Date(now);
    toDate.setMonth(toDate.getMonth() + 1);

    return {
        userMode: 'new',
        userId: '',
        newUser: {
            email: '',
            password: generateRandomPassword(),
            nickName: '',
            role: 'VIP',
            disabled: false
        },
        accountNo: '',
        level: 4,
        nodeMultiple: 2,
        nodePrefix: '8',
        fromDate: formatBeijingDateTimeForLocal(fromDate),
        toDate: formatBeijingDateTimeForLocal(toDate),
        periodType: 'MONTHLY',
        uuid: '',
        authCode: '',
        maxConnections: 100,
        maxIps: 2,
        speed: 2048,
        bandwidth: 500,
        downloadMbps: 12,
        uploadMbps: 8,
        disabled: false,
        remark: '',
        tagIds: [],
        amount: '',
        paymentMethod: ''
    };
}

export function validateAccountOnboardingForm(item) {
    const errors = {};
    if (item.userMode === 'existing') {
        if (!item.userId) errors.userId = '请选择用户';
    } else {
        const email = (item.newUser?.email || '').trim();
        if (!email) {
            errors.newUserEmail = '邮箱地址不能为空';
        } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
            errors.newUserEmail = '请输入有效的邮箱地址';
        }
        if (!item.newUser?.password || item.newUser.password.trim().length < 6) {
            errors.newUserPassword = '密码长度至少为 6 个字符';
        }
    }

    if (!item.periodType) errors.periodType = '请选择统计周期类型';
    if (!item.nodeMultiple) errors.nodeMultiple = '请填写倍数';
    if (!item.remark || !item.remark.trim()) errors.remark = '请填写账号备注名';
    if (item.fromDate && item.toDate && new Date(item.toDate) <= new Date(item.fromDate)) {
        errors.toDate = '到期时间必须晚于开始时间';
    }

    if (hasInitialAmount(item)) {
        const amount = Number(item.amount);
        if (!Number.isFinite(amount) || amount <= 0) errors.amount = '请输入有效金额';
        if (!item.paymentMethod) errors.paymentMethod = '请选择付款方式';
    }
    return errors;
}

export function buildOnboardingUserSource(item) {
    if (item.userMode === 'existing') {
        return { userId: Number(item.userId), newUser: null };
    }
    return {
        userId: null,
        newUser: {
            email: item.newUser.email.trim(),
            password: item.newUser.password,
            nickName: item.newUser.nickName || null,
            role: item.newUser.role || 'VIP',
            disabled: item.newUser.disabled ? 1 : 0
        }
    };
}

export function buildInitialTransactionPayload(item) {
    if (!hasInitialAmount(item)) {
        return { amount: null, paymentMethod: null };
    }
    return { amount: Number(item.amount), paymentMethod: item.paymentMethod };
}

function hasInitialAmount(item) {
    return item.amount !== null && item.amount !== undefined && String(item.amount).trim() !== '';
}
