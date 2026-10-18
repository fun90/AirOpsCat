const MAX_AGE = 60 * 60 * 24 * 365;

// 读取 cookie 中保存的筛选项，仅保留 defaults 中存在且类型一致的键
export function loadFilterCookie(name, defaults) {
    const result = { filters: { ...defaults }, searchQuery: '' };
    const match = document.cookie.split('; ').find(item => item.startsWith(`${name}=`));
    if (!match) {
        return result;
    }
    try {
        const saved = JSON.parse(decodeURIComponent(match.substring(name.length + 1)));
        Object.keys(defaults).forEach(key => {
            if (typeof saved.filters?.[key] === typeof defaults[key]) {
                result.filters[key] = saved.filters[key];
            }
        });
        if (typeof saved.searchQuery === 'string') {
            result.searchQuery = saved.searchQuery;
        }
    } catch (error) {
        // cookie 损坏时回退默认值
    }
    return result;
}

export function saveFilterCookie(name, filters, searchQuery) {
    const value = encodeURIComponent(JSON.stringify({ filters, searchQuery }));
    document.cookie = `${name}=${value}; path=/; max-age=${MAX_AGE}; SameSite=Lax`;
}
