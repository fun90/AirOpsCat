/**
 * SearchDropdown - 通用搜索下拉框组件
 * 基于 Tabler UI 1.3.2 和 petite-vue 0.4.1
 */
export class SearchDropdown {
    constructor(options = {}) {
        this.options = {
            // API配置
            apiUrl: '',                         // API接口地址
            searchParam: 'search',              // 搜索参数名
            sizeParam: 'size',                  // 分页大小参数名
            defaultSize: 20,                    // 默认页面大小
            
            // 搜索配置
            minQueryLength: 2,                  // 最小查询长度（非中文）
            minQueryLengthChinese: 1,           // 最小查询长度（中文）
            debounceDelay: 300,                 // 防抖延迟（毫秒）
            placeholder: '请输入搜索内容...',    // 占位符
            disabled: false,                    // 是否禁用
            
            // 显示配置
            maxHeight: '200px',                 // 下拉框最大高度
            noResultsText: '未找到匹配项',       // 无结果提示
            loadingText: '搜索中...',          // 加载中提示
            minLengthText: '请输入至少{n}个字符', // 最小长度提示（非中文）
            minLengthTextChinese: '请输入至少1个中文字符进行搜索', // 最小长度提示（中文）
            
            // DOM配置
            componentId: null,                  // 组件DOM ID前缀
            validationField: null,              // 验证字段名
            vueInstance: null,                  // Vue实例引用
            
            // 数据处理
            formatItem: null,                   // 格式化单项数据函数
            formatDisplay: null,                // 格式化显示文本函数
            
            // 事件回调
            onSelect: null,                     // 选择项目回调
            onChange: null,                     // 输入变化回调
            onSearch: null,                     // 搜索前回调
            onError: null,                      // 错误回调
            
            // 缓存配置
            enableCache: true,                  // 是否启用缓存
            cacheExpiration: 300000,            // 缓存过期时间（毫秒）
            
            // 样式配置
            cssClass: '',                       // 额外CSS类
            
            // 校验配置
            validation: {
                enabled: false,                 // 是否启用校验
                rules: [],                      // 校验规则数组
                validateOn: ['blur', 'change'], // 校验触发时机 ['input', 'blur', 'change']
                showErrors: true,               // 是否显示错误信息
                fieldName: null,                // 校验字段名（用于Vue实例错误存储）
            },
            
            ...options
        };
        
        // 内部状态
        this.searchText = '';
        this.filteredItems = [];
        this.showDropdown = false;
        this.selectedIndex = -1;
        this.isLoading = false;
        this.cache = new Map();
        this.debounceTimer = null;
        this.selectedItem = null;
        
        // 校验状态
        this.validationErrors = [];
        this.isValid = true;
        this.hasBeenValidated = false;
        
        // DOM元素引用
        this.domElements = {
            dropdown: null,
            input: null,
            menu: null,
            loading: null,
            results: null,
            noResults: null,
            minLength: null,
            validation: null
        };
        this.isBindToDOM = false;
        
        // 绑定方法
        this.handleInput = this.handleInput.bind(this);
        this.handleFocus = this.handleFocus.bind(this);
        this.handleBlur = this.handleBlur.bind(this);
        this.handleKeydown = this.handleKeydown.bind(this);
        this.selectItem = this.selectItem.bind(this);
        this.performSearch = this.performSearch.bind(this);
        this.hasChinese = this.hasChinese.bind(this);
        this.getCurrentMinLength = this.getCurrentMinLength.bind(this);
        this.bindToDOM = this.bindToDOM.bind(this);
        this.updateUI = this.updateUI.bind(this);
        this.validate = this.validate.bind(this);
        this.clearValidation = this.clearValidation.bind(this);
        this.setValidationError = this.setValidationError.bind(this);
    }
    
    /**
     * 防抖函数
     */
    debounce(func, delay) {
        return (...args) => {
            if (this.debounceTimer) {
                clearTimeout(this.debounceTimer);
            }
            this.debounceTimer = setTimeout(() => func.apply(this, args), delay);
        };
    }
    
    /**
     * 检测文本是否包含中文字符
     */
    hasChinese(text) {
        return /[\u4e00-\u9fa5]/.test(text);
    }
    
    /**
     * 获取当前搜索文本的最小长度要求
     */
    getCurrentMinLength() {
        if (this.searchText && this.hasChinese(this.searchText)) {
            return this.options.minQueryLengthChinese;
        }
        return this.options.minQueryLength;
    }
    
    /**
     * 处理输入事件
     */
    handleInput(event) {
        this.searchText = event.target.value;
        this.selectedIndex = -1;
        this.selectedItem = null;
        
        // 触发onChange回调
        if (this.options.onChange) {
            this.options.onChange(this.searchText, this.selectedItem);
        }
        
        if (!this.searchText) {
            this.filteredItems = [];
            this.showDropdown = false;
            return;
        }
        
        const currentMinLength = this.getCurrentMinLength();
        if (this.searchText.length >= currentMinLength) {
            this.debouncedSearch();
        } else {
            this.filteredItems = [];
            this.showDropdown = false;
            this.updateUI();
        }
    }
    
    /**
     * 处理焦点事件
     */
    handleFocus() {
        const currentMinLength = this.getCurrentMinLength();
        if (this.searchText && this.searchText.length >= currentMinLength) {
            this.debouncedSearch();
        } else if (this.filteredItems.length > 0) {
            this.showDropdown = true;
            this.updateUI();
        }
    }
    
    /**
     * 处理失焦事件
     */
    handleBlur() {
        setTimeout(() => {
            this.showDropdown = false;
            this.selectedIndex = -1;
        }, 200);
    }
    
    /**
     * 处理键盘事件
     */
    handleKeydown(event) {
        if (!this.showDropdown) return;
        
        switch (event.key) {
            case 'ArrowDown':
                event.preventDefault();
                this.selectedIndex = Math.min(
                    this.selectedIndex + 1, 
                    this.filteredItems.length - 1
                );
                break;
            case 'ArrowUp':
                event.preventDefault();
                this.selectedIndex = Math.max(this.selectedIndex - 1, 0);
                break;
            case 'Enter':
                event.preventDefault();
                if (this.selectedIndex >= 0 && this.selectedIndex < this.filteredItems.length) {
                    this.selectItem(this.filteredItems[this.selectedIndex]);
                }
                break;
            case 'Escape':
                event.preventDefault();
                this.showDropdown = false;
                this.selectedIndex = -1;
                break;
        }
    }
    
    /**
     * 选择项目
     */
    selectItem(item) {
        this.selectedItem = item;
        this.searchText = this.options.formatDisplay ? this.options.formatDisplay(item) : (item.name || item.label || '');
        this.showDropdown = false;
        this.selectedIndex = -1;
        
        // 触发选择回调
        if (this.options.onSelect) {
            this.options.onSelect(item);
        }
        
        // 触发变化回调
        if (this.options.onChange) {
            this.options.onChange(this.searchText, item);
        }
        
        // 如果配置了change时校验
        if (this.options.validation.enabled && this.options.validation.validateOn.includes('change')) {
            this.validate();
        }
        
        this.updateUI();
    }
    
    /**
     * 执行搜索
     */
    async performSearch() {
        const currentMinLength = this.getCurrentMinLength();
        if (!this.options.apiUrl || !this.searchText || this.searchText.length < currentMinLength) {
            return;
        }
        
        // 检查缓存
        const cacheKey = `${this.options.apiUrl}-${this.searchText.toLowerCase()}`;
        if (this.options.enableCache && this.cache.has(cacheKey)) {
            const cacheData = this.cache.get(cacheKey);
            if (Date.now() - cacheData.timestamp < this.options.cacheExpiration) {
                this.filteredItems = cacheData.data;
                this.showDropdown = this.filteredItems.length > 0;
                return;
            } else {
                this.cache.delete(cacheKey);
            }
        }
        
        // 触发搜索前回调
        if (this.options.onSearch) {
            this.options.onSearch(this.searchText);
        }
        
        this.isLoading = true;
        this.updateUI();
        
        try {
            const params = new URLSearchParams();
            params.append(this.options.searchParam, this.searchText);
            params.append(this.options.sizeParam, this.options.defaultSize);
            
            const url = `${this.options.apiUrl}?${params.toString()}`;
            const response = await fetch(url);
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            
            const data = await response.json();
            let results = [];
            
            if (data.records && Array.isArray(data.records)) {
                results = data.records.map(item => {
                    if (this.options.formatItem) {
                        return this.options.formatItem(item);
                    }
                    return item;
                });
            }
            
            // 缓存结果
            if (this.options.enableCache) {
                this.cache.set(cacheKey, {
                    data: results,
                    timestamp: Date.now()
                });
            }
            
            this.filteredItems = results;
            this.showDropdown = results.length > 0;
            
        } catch (error) {
            console.error('Search failed:', error);
            this.filteredItems = [];
            this.showDropdown = false;
            
            // 触发错误回调
            if (this.options.onError) {
                this.options.onError(error);
            }
        } finally {
            this.isLoading = false;
            this.updateUI();
        }
    }
    
    /**
     * 初始化防抖搜索
     */
    init() {
        this.debouncedSearch = this.debounce(this.performSearch, this.options.debounceDelay);
    }
    
    /**
     * 设置值
     */
    setValue(text, item = null) {
        this.searchText = text;
        this.selectedItem = item;
        this.filteredItems = [];
        this.showDropdown = false;
        this.updateUI();
    }
    
    /**
     * 获取值
     */
    getValue() {
        return {
            text: this.searchText,
            item: this.selectedItem
        };
    }
    
    /**
     * 清空值
     */
    clear() {
        this.searchText = '';
        this.selectedItem = null;
        this.filteredItems = [];
        this.showDropdown = false;
        this.selectedIndex = -1;
        
        if (this.options.onChange) {
            this.options.onChange('', null);
        }
        
        // 清除校验状态
        this.clearValidation();
        
        this.updateUI();
    }
    
    /**
     * 清空缓存
     */
    clearCache() {
        this.cache.clear();
    }
    
    /**
     * 设置禁用状态
     */
    setDisabled(disabled) {
        this.options.disabled = disabled;
        if (disabled) {
            this.clear();
        }
        this.updateUI();
    }
    
    /**
     * 校验输入值
     */
    validate() {
        if (!this.options.validation.enabled || !this.options.validation.rules.length) {
            return true;
        }
        
        this.clearValidation();
        this.hasBeenValidated = true;
        
        const value = this.searchText;
        const selectedItem = this.selectedItem;
        
        for (const rule of this.options.validation.rules) {
            const result = this.executeValidationRule(rule, value, selectedItem);
            if (result !== true) {
                this.validationErrors.push(result);
                this.isValid = false;
            }
        }
        
        // 更新Vue实例的校验状态
        if (this.options.vueInstance && this.options.validation.fieldName) {
            if (!this.options.vueInstance.validationErrors) {
                this.options.vueInstance.validationErrors = {};
            }
            
            if (this.validationErrors.length > 0) {
                this.options.vueInstance.validationErrors[this.options.validation.fieldName] = this.validationErrors[0];
            } else {
                delete this.options.vueInstance.validationErrors[this.options.validation.fieldName];
            }
        }
        
        this.updateUI();
        return this.isValid;
    }
    
    /**
     * 执行单个校验规则
     */
    executeValidationRule(rule, value, selectedItem) {
        switch (rule.type) {
            case 'required':
                if (!value || value.trim() === '') {
                    return rule.message || '此字段为必填项';
                }
                break;
                
            case 'requiredSelection':
                if (!selectedItem) {
                    return rule.message || '请选择一个选项';
                }
                break;
                
            case 'minLength':
                if (value && value.length < rule.value) {
                    return rule.message || `最少需要${rule.value}个字符`;
                }
                break;
                
            case 'maxLength':
                if (value && value.length > rule.value) {
                    return rule.message || `最多允许${rule.value}个字符`;
                }
                break;
                
            case 'pattern':
                if (value && !rule.value.test(value)) {
                    return rule.message || '输入格式不正确';
                }
                break;
                
            case 'custom':
                if (typeof rule.validator === 'function') {
                    const result = rule.validator(value, selectedItem);
                    if (result !== true) {
                        return result || rule.message || '校验失败';
                    }
                }
                break;
                
            default:
                console.warn(`未知的校验规则类型: ${rule.type}`);
        }
        
        return true;
    }
    
    /**
     * 清除校验状态
     */
    clearValidation() {
        this.validationErrors = [];
        this.isValid = true;
        
        // 清除Vue实例的校验状态
        if (this.options.vueInstance && this.options.validation.fieldName) {
            if (this.options.vueInstance.validationErrors) {
                delete this.options.vueInstance.validationErrors[this.options.validation.fieldName];
            }
        }
    }
    
    /**
     * 设置校验错误
     */
    setValidationError(message) {
        this.validationErrors = [message];
        this.isValid = false;
        this.hasBeenValidated = true;
        
        // 更新Vue实例的校验状态
        if (this.options.vueInstance && this.options.validation.fieldName) {
            if (!this.options.vueInstance.validationErrors) {
                this.options.vueInstance.validationErrors = {};
            }
            this.options.vueInstance.validationErrors[this.options.validation.fieldName] = message;
        }
        
        this.updateUI();
    }
    
    /**
     * 绑定到DOM元素
     */
    bindToDOM(componentId, validationField, vueInstance) {
        if (this.isBindToDOM) {
            return; // 防止重复绑定
        }
        
        this.options.componentId = componentId;
        this.options.validationField = validationField;
        this.options.vueInstance = vueInstance;
        
        // 获取DOM元素
        this.domElements.dropdown = document.getElementById(`search-dropdown-${componentId}`);
        this.domElements.input = document.getElementById(`search-input-${componentId}`);
        this.domElements.menu = document.getElementById(`search-menu-${componentId}`);
        this.domElements.loading = document.getElementById(`search-loading-${componentId}`);
        this.domElements.results = document.getElementById(`search-results-${componentId}`);
        this.domElements.noResults = document.getElementById(`search-no-results-${componentId}`);
        this.domElements.minLength = document.getElementById(`search-min-length-${componentId}`);
        this.domElements.validation = document.getElementById(`search-validation-${componentId}`);
        
        if (!this.domElements.dropdown || !this.domElements.input) {
            console.warn(`搜索下拉框DOM绑定失败: ${componentId}`);
            return;
        }
        
        // 绑定事件
        this.domElements.input.addEventListener('input', (e) => {
            this.searchText = e.target.value;
            this.handleInput(e);
            
            // 如果配置了input时校验
            if (this.options.validation.enabled && this.options.validation.validateOn.includes('input')) {
                this.validate();
            }
            this.updateUI();
        });
        
        this.domElements.input.addEventListener('focus', (e) => {
            this.handleFocus(e);
            this.updateUI();
        });
        
        this.domElements.input.addEventListener('blur', (e) => {
            this.handleBlur(e);
            
            // 如果配置了blur时校验
            if (this.options.validation.enabled && this.options.validation.validateOn.includes('blur')) {
                setTimeout(() => {
                    this.validate();
                    this.updateUI();
                }, 200);
            } else {
                setTimeout(() => this.updateUI(), 200);
            }
        });
        
        this.domElements.input.addEventListener('keydown', (e) => {
            this.handleKeydown(e);
            setTimeout(() => this.updateUI(), 10);
        });
        
        this.isBindToDOM = true;
        this.updateUI();
    }
    
    /**
     * 更新UI状态
     */
    updateUI() {
        if (!this.isBindToDOM || !this.domElements.input) {
            return;
        }
        
        const { input, dropdown, menu, loading, results, noResults, minLength, validation } = this.domElements;
        
        // 更新输入框状态
        if (input.value !== this.searchText) {
            input.value = this.searchText;
        }
        input.disabled = this.options.disabled;
        input.placeholder = this.options.placeholder;
        
        // 更新下拉框显示状态
        const shouldShow = this.showDropdown || this.isLoading;
        dropdown.classList.toggle('show', shouldShow);
        menu.classList.toggle('show', shouldShow);
        
        // 更新加载状态
        if (loading) {
            loading.style.display = this.isLoading ? 'block' : 'none';
        }
        
        // 更新搜索结果
        if (results) {
            results.innerHTML = '';
            if (this.filteredItems && this.filteredItems.length > 0) {
                this.filteredItems.forEach((item, index) => {
                    const button = document.createElement('button');
                    button.type = 'button';
                    button.className = 'dropdown-item';
                    if (index === this.selectedIndex) {
                        button.classList.add('active');
                    }
                    button.textContent = item.name;
                    button.addEventListener('mousedown', (e) => {
                        e.preventDefault();
                        this.selectItem(item);
                        this.updateUI();
                    });
                    button.addEventListener('mouseenter', () => {
                        this.selectedIndex = index;
                        this.updateUI();
                    });
                    results.appendChild(button);
                });
            }
        }
        
        // 更新提示信息
        if (noResults) {
            const currentMinLength = this.getCurrentMinLength();
            const showNoResults = !this.isLoading && 
                                this.filteredItems.length === 0 && 
                                this.searchText && 
                                this.searchText.length >= currentMinLength;
            noResults.style.display = showNoResults ? 'block' : 'none';
        }
        
        if (minLength) {
            const currentMinLength = this.getCurrentMinLength();
            const showMinLength = !this.isLoading && 
                                this.searchText && 
                                this.searchText.length < currentMinLength;
            
            // 动态更新提示文本
            const isChinese = this.hasChinese(this.searchText);
            minLength.textContent = isChinese ? 
                this.options.minLengthTextChinese : 
                this.options.minLengthText.replace('{n}', this.options.minQueryLength);
            
            minLength.style.display = showMinLength ? 'block' : 'none';
        }
        
        // 更新验证错误
        if (validation) {
            let hasValidationError = false;
            let errorMessage = '';
            
            // 优先使用组件内部的校验状态
            if (this.options.validation.enabled && this.hasBeenValidated && !this.isValid && this.options.validation.showErrors) {
                hasValidationError = true;
                errorMessage = this.validationErrors[0] || '校验失败';
            }
            // 回退到Vue实例的校验状态（向后兼容）
            else if (this.options.vueInstance && this.options.validationField) {
                const vueError = this.options.vueInstance.validationErrors && 
                               this.options.vueInstance.validationErrors[this.options.validationField];
                if (vueError) {
                    hasValidationError = true;
                    errorMessage = vueError;
                }
            }
            
            // 设置输入框校验状态
            input.classList.toggle('is-invalid', hasValidationError);
            
            // 显示错误信息
            if (hasValidationError) {
                validation.textContent = errorMessage;
                validation.style.display = 'block';
            } else {
                validation.style.display = 'none';
            }
        }
    }
    
    /**
     * 销毁组件
     */
    destroy() {
        if (this.debounceTimer) {
            clearTimeout(this.debounceTimer);
        }
        this.clearCache();
        
        // 清理DOM绑定
        if (this.isBindToDOM && this.domElements.input) {
            this.domElements.input.removeEventListener('input', this.handleInput);
            this.domElements.input.removeEventListener('focus', this.handleFocus);
            this.domElements.input.removeEventListener('blur', this.handleBlur);
            this.domElements.input.removeEventListener('keydown', this.handleKeydown);
        }
        
        this.isBindToDOM = false;
        this.domElements = {};
    }
}

// 创建工厂函数
export function createSearchDropdown(options) {
    const dropdown = new SearchDropdown(options);
    dropdown.init();
    return dropdown;
}

// 校验规则预设
export const ValidationRules = {
    // 必填规则
    required: (message = '此字段为必填项') => ({
        type: 'required',
        message: message
    }),
    
    // 必须选择项目
    requiredSelection: (message = '请选择一个选项') => ({
        type: 'requiredSelection', 
        message: message
    }),
    
    // 最小长度
    minLength: (length, message = null) => ({
        type: 'minLength',
        value: length,
        message: message || `最少需要${length}个字符`
    }),
    
    // 最大长度
    maxLength: (length, message = null) => ({
        type: 'maxLength',
        value: length,
        message: message || `最多允许${length}个字符`
    }),
    
    // 正则表达式
    pattern: (regex, message = '输入格式不正确') => ({
        type: 'pattern',
        value: regex,
        message: message
    }),
    
    // 自定义校验
    custom: (validator, message = '校验失败') => ({
        type: 'custom',
        validator: validator,
        message: message
    }),
    
    // 常用预设
    email: () => ({
        type: 'pattern',
        value: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
        message: '请输入有效的邮箱地址'
    }),
    
    phone: () => ({
        type: 'pattern',
        value: /^1[3-9]\d{9}$/,
        message: '请输入有效的手机号码'
    }),
    
    ip: () => ({
        type: 'pattern',
        value: /^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/,
        message: '请输入有效的IP地址'
    })
};

// 预设配置
export const SearchDropdownPresets = {
    // 账户搜索
    account: (baseUrl = '/api/admin') => ({
        apiUrl: `${baseUrl}/accounts`,
        placeholder: '搜索账户（按备注）...',
        formatItem: (item) => ({
            id: item.id,
            name: item.remark,
            data: item
        })
    }),
    
    // 域名搜索
    domain: (baseUrl = '/api/admin') => ({
        apiUrl: `${baseUrl}/domains`,
        placeholder: '搜索域名...',
        formatItem: (item) => ({
            id: item.id,
            name: item.domain || item.id,
            data: item
        })
    }),
    
    // 服务器搜索
    server: (baseUrl = '/api/admin') => ({
        apiUrl: `${baseUrl}/servers`,
        placeholder: '搜索服务器（按IP或名称）...',
        formatItem: (item) => ({
            id: item.id,
            name: item.ip + (item.name ? ` (${item.name})` : ''),
            data: item
        })
    })
};