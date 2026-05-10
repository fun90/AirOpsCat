/**
 * TomSelect 远程搜索辅助函数
 */

export function clearTomSelectSearchInput(instance) {
    if (!instance) {
        return;
    }

    setTimeout(() => {
        instance.setTextboxValue('');
        if (instance.control_input) {
            instance.control_input.value = '';
        }
        instance.lastQuery = null;
        instance.refreshOptions(false);
    }, 0);
}

export function withTomSelectSearchClear(config = {}) {
    const originalOnItemAdd = config.onItemAdd;

    return {
        ...config,
        onItemAdd(value, item) {
            if (originalOnItemAdd) {
                originalOnItemAdd.call(this, value, item);
            }
            clearTomSelectSearchInput(this);
        }
    };
}

/**
 * 创建远程搜索配置
 * @param {Object} options 配置选项
 * @param {string} options.apiUrl - API 端点
 * @param {string} options.valueField - 值字段名
 * @param {string} options.labelField - 显示字段名
 * @param {string[]} options.searchField - 搜索字段数组
 * @param {string} options.placeholder - 占位符
 * @param {Function} options.onChange - 值变化回调
 * @param {number} [options.minQueryLength=2] - 最小查询长度
 * @param {number} [options.pageSize=20] - 每页数量
 * @param {Object} [options.render] - 自定义渲染函数
 * @param {Function} [options.dataTransform] - 数据转换函数
 * @param {string[]} [options.plugins] - TomSelect 插件
 * @param {Array} [options.options] - 初始选项
 * @param {number} [options.maxOptions] - 最大选项数
 * @param {Function} [options.onItemAdd] - 选项添加回调
 * @returns {Object} TomSelect 配置对象
 */
export function createRemoteSearchConfig(options) {
    const {
        apiUrl,
        valueField,
        labelField,
        searchField,
        placeholder,
        onChange,
        minQueryLength = 2,
        pageSize = 20,
        render,
        dataTransform,
        plugins,
        options: initialOptions,
        maxOptions,
        onItemAdd
    } = options;

    const config = withTomSelectSearchClear({
        valueField,
        labelField,
        searchField,
        placeholder,
        load: (query, callback) => {
            if (!query.length || query.length < minQueryLength) {
                callback();
                return;
            }
            fetch(`${apiUrl}?search=${encodeURIComponent(query)}&size=${pageSize}`)
                .then(response => response.json())
                .then(data => {
                    const records = data.records || data;
                    callback(dataTransform ? dataTransform(records) : records);
                })
                .catch(() => callback());
        },
        onChange,
        onItemAdd
    });

    if (render) {
        config.render = render;
    }

    if (plugins) {
        config.plugins = plugins;
    }

    if (initialOptions) {
        config.options = initialOptions;
    }

    if (maxOptions) {
        config.maxOptions = maxOptions;
    }

    return config;
}

/**
 * 在 modal 显示时重新初始化 TomSelect
 * @param {string} modalId - Modal 元素 ID
 * @param {string} selectId - Select 元素 ID
 * @param {Object} config - TomSelect 配置
 * @param {Object} context - Vue 实例上下文
 * @param {string} instanceKey - 实例存储的键名
 * @param {Function} [afterInit] - 初始化后的回调
 */
export function initSelectOnModalShow(modalId, selectId, config, context, instanceKey, afterInit) {
    document.getElementById(modalId).addEventListener('show.bs.modal', () => {
        if (context[instanceKey]) {
            context[instanceKey].destroy();
        }
        context[instanceKey] = new TomSelect(document.getElementById(selectId), config);
        if (afterInit) {
            afterInit(context[instanceKey]);
        }
    });
}

/**
 * 预配置：用户搜索
 * @param {Function} onChange - 值变化回调
 * @param {Array} [options] - 初始选项
 * @returns {Object} TomSelect 配置
 */
export function createUserSearch(onChange, options) {
    return createRemoteSearchConfig({
        apiUrl: '/api/admin/users',
        valueField: 'id',
        labelField: 'nickName',
        searchField: ['nickName', 'email'],
        placeholder: '请搜索用户...',
        onChange,
        options
    });
}

/**
 * 预配置：账户搜索
 * @param {Function} onChange - 值变化回调，接收 (value, option, instance) 参数
 * @returns {Object} TomSelect 配置
 */
export function createAccountSearch(onChange) {
    const config = createRemoteSearchConfig({
        apiUrl: '/api/admin/accounts',
        valueField: 'id',
        labelField: 'displayName',
        searchField: ['remark', 'accountNo', 'authCode'],
        placeholder: '搜索账户备注、账号或认证码...',
        dataTransform: (records) => records.map(item => ({
            ...item,
            displayName: `${item.remark || '未命名账户'}${item.accountNo ? ` (${item.accountNo})` : ''}`
        })),
        render: {
            option: (data, escape) => `<div>${escape(data.remark || '未命名账户')}${data.accountNo ? ` (${escape(data.accountNo)})` : ''}</div>`,
            item: (data, escape) => `<div>${escape(data.remark || '未命名账户')}${data.accountNo ? ` (${escape(data.accountNo)})` : ''}</div>`
        },
        onChange: null // 稍后设置
    });

    // 包装 onChange 以提供 option 对象
    config.onChange = function(value) {
        const option = value ? this.options[value] : null;
        onChange(value, option, this);
    };

    return config;
}

/**
 * 预配置：服务器搜索
 * @param {Function} onChange - 值变化回调
 * @param {Function} [formatLabel] - 自定义标签格式化函数
 * @returns {Object} TomSelect 配置
 */
export function createServerSearch(onChange, formatLabel) {
    return createRemoteSearchConfig({
        apiUrl: '/api/admin/servers',
        valueField: 'id',
        labelField: 'name',
        searchField: ['name', 'ip'],
        placeholder: '搜索服务器...',
        dataTransform: formatLabel ? (records) => records.map(item => ({
            ...item,
            name: formatLabel(item)
        })) : undefined,
        onChange
    });
}

/**
 * 预配置：域名搜索
 * @param {Function} onChange - 值变化回调
 * @returns {Object} TomSelect 配置
 */
export function createDomainSearch(onChange) {
    return createRemoteSearchConfig({
        apiUrl: '/api/admin/domains',
        valueField: 'id',
        labelField: 'domain',
        searchField: ['domain'],
        placeholder: '搜索域名...',
        onChange
    });
}
