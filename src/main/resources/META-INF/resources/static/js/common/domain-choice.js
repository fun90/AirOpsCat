/**
 * 域名单选：输入框 + datalist。留空表示不使用；输入 NEW_DOMAIN_TEXT 表示新建；
 * 其余必须与列表项完全一致，否则返回 INVALID_CHOICE，由表单校验拦截。
 */
export const NEW_DOMAIN_TEXT = '新建域名…';
export const INVALID_CHOICE = 'invalid';

export function choiceFromText(text, records) {
    const value = (text || '').trim();
    if (!value) {
        return '';
    }
    if (value === NEW_DOMAIN_TEXT) {
        return '__new__';
    }
    const record = (records || []).find(item => item.fullName === value);
    return record ? String(record.id) : INVALID_CHOICE;
}

/** 新建域名时选所属域名：文本须与域名列表项一致，返回其 id，否则空串 */
export function domainIdFromText(text, domains) {
    const value = (text || '').trim();
    const domain = (domains || []).find(item => item.domain === value);
    return domain ? String(domain.id) : '';
}
