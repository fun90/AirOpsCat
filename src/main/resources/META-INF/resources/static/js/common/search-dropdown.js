 /**
 * SearchDropdown - 简化的搜索下拉框组件
 */
export class SearchDropdown {
    constructor(options = {}) {
        this.options = {
            apiUrl: '',
            searchParam: 'search',
            sizeParam: 'size',
            defaultSize: 20,
            minQueryLength: 2,
            debounceDelay: 300,
            placeholder: '请输入搜索内容...',
            noResultsText: '未找到匹配项',
            loadingText: '搜索中...',
            formatItem: null,
            formatDisplay: null,
            onSelect: null,
            onChange: null,
            enableCache: true,
            cacheExpiration: 300000,
            defaultOptions: null,
            multiSelect: false,
            ...options
        };

        // 内部状态
        this.searchText = '';
        this.filteredItems = [];
        this.showDropdown = false;
        this.selectedIndex = -1;
        this.isLoading = false;
        this.selectedItem = null;
        this.selectedItems = [];
        this.cache = new Map();
        this.debounceTimer = null;

        // DOM元素
        this.elements = {};
        this.isBound = false;

        // 绑定方法
        this.performSearch = this.performSearch.bind(this);
        this.debouncedSearch = this.debounce(this.performSearch, this.options.debounceDelay);
    }
    
    /**
     * 防抖函数
     */
    debounce(func, delay) {
        return (...args) => {
            clearTimeout(this.debounceTimer);
            this.debounceTimer = setTimeout(() => func.apply(this, args), delay);
        };
    }
    
    /**
     * 绑定到DOM
     */
    bindToDOM(componentId) {
        if (this.isBound) return;

        this.elements = {
            dropdown: document.getElementById(`search-dropdown-${componentId}`),
            input: document.getElementById(`search-input-${componentId}`),
            clear: document.getElementById(`search-clear-${componentId}`),
            menu: document.getElementById(`search-menu-${componentId}`),
            loading: document.getElementById(`search-loading-${componentId}`),
            results: document.getElementById(`search-results-${componentId}`),
            noResults: document.getElementById(`search-no-results-${componentId}`),
            tags: document.getElementById(`search-tags-${componentId}`)
        };

        if (!this.elements.input) {
            console.warn(`搜索下拉框DOM绑定失败: ${componentId}`);
            return;
        }

        this.setupEventListeners();
        this.isBound = true;
        this.updateUI();
    }
    
    /**
     * 设置事件监听器
     */
    setupEventListeners() {
        const { input, clear } = this.elements;
        
        input.addEventListener('input', (e) => {
            this.searchText = e.target.value;
            this.selectedIndex = -1;
            if (!this.options.multiSelect) {
                this.selectedItem = null;
            }

            if (this.options.onChange) {
                this.options.onChange(this.searchText, this.options.multiSelect ? this.selectedItems : this.selectedItem);
            }

            if (this.searchText.length >= this.options.minQueryLength) {
                this.debouncedSearch();
            } else if (this.searchText.length === 0 && this.options.defaultOptions) {
                this.showDefaultOptions();
            } else {
                this.hideDropdown();
            }
        });
        
        input.addEventListener('focus', () => {
            if (this.searchText.length === 0 && this.options.defaultOptions) {
                this.showDefaultOptions();
            } else if (this.filteredItems.length > 0) {
                this.showDropdown = true;
                this.updateUI();
            }
        });
        
        input.addEventListener('blur', () => {
            setTimeout(() => {
                this.hideDropdown();
            }, 150);
        });
        
        input.addEventListener('keydown', (e) => {
            this.handleKeydown(e);
        });

        if (clear) {
            clear.addEventListener('mousedown', (e) => {
                e.preventDefault();
            });

            clear.addEventListener('click', () => {
                this.clear();
                input.focus();
            });
        }
    }
    
    /**
     * 处理键盘事件
     */
    handleKeydown(event) {
        if (!this.showDropdown || this.filteredItems.length === 0) return;
        
        switch (event.key) {
            case 'ArrowDown':
                event.preventDefault();
                this.selectedIndex = Math.min(this.selectedIndex + 1, this.filteredItems.length - 1);
                this.updateUI();
                break;
            case 'ArrowUp':
                event.preventDefault();
                this.selectedIndex = Math.max(this.selectedIndex - 1, 0);
                this.updateUI();
                break;
            case 'Enter':
                event.preventDefault();
                if (this.selectedIndex >= 0) {
                    this.selectItem(this.filteredItems[this.selectedIndex]);
                }
                break;
            case 'Escape':
                event.preventDefault();
                this.hideDropdown();
                break;
        }
    }
    
    /**
     * 显示默认选项
     */
    showDefaultOptions() {
        if (!this.options.defaultOptions) return;

        let options = this.options.defaultOptions;
        if (typeof options === 'function') {
            options = options();
        }

        if (this.options.formatItem) {
            options = options.map(this.options.formatItem);
        }

        this.filteredItems = options;
        this.showDropdown = true;
        this.updateUI();
    }

    /**
     * 执行搜索
     */
    async performSearch() {
        if (!this.options.apiUrl || this.searchText.length < this.options.minQueryLength) {
            return;
        }
        
        // 检查缓存
        const cacheKey = `${this.options.apiUrl}-${this.searchText.toLowerCase()}`;
        if (this.options.enableCache && this.cache.has(cacheKey)) {
            const cached = this.cache.get(cacheKey);
            if (Date.now() - cached.timestamp < this.options.cacheExpiration) {
                this.filteredItems = cached.data;
                this.showDropdown = true;
                this.updateUI();
                return;
            }
            this.cache.delete(cacheKey);
        }
        
        this.isLoading = true;
        this.updateUI();
        
        try {
            const params = new URLSearchParams({
                [this.options.searchParam]: this.searchText,
                [this.options.sizeParam]: this.options.defaultSize
            });
            
            const response = await fetch(`${this.options.apiUrl}?${params}`);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            
            const data = await response.json();
            let results = data.records || [];
            
            if (this.options.formatItem) {
                results = results.map(this.options.formatItem);
            }
            
            // 缓存结果
            if (this.options.enableCache) {
                this.cache.set(cacheKey, {
                    data: results,
                    timestamp: Date.now()
                });
            }
            
            this.filteredItems = results;
            this.showDropdown = true;
            
        } catch (error) {
            console.error('搜索失败:', error);
            this.filteredItems = [];
            this.showDropdown = false;
        } finally {
            this.isLoading = false;
            this.updateUI();
        }
    }
    
    /**
     * 选择项目
     */
    selectItem(item) {
        if (this.options.multiSelect) {
            const index = this.selectedItems.findIndex(i => i.id === item.id);
            if (index >= 0) {
                this.selectedItems.splice(index, 1);
            } else {
                this.selectedItems.push(item);
            }
            this.searchText = '';
        } else {
            this.selectedItem = item;
            this.searchText = this.options.formatDisplay ?
                this.options.formatDisplay(item) :
                (item.name || item.label || '');
            this.hideDropdown();
        }

        if (this.options.onSelect) {
            this.options.onSelect(this.options.multiSelect ? [...this.selectedItems] : item);
        }

        if (this.options.onChange) {
            this.options.onChange(this.searchText, this.options.multiSelect ? this.selectedItems : item);
        }

        this.updateUI();
    }
    
    /**
     * 渲染已选标签
     */
    renderSelectedTags() {
        if (!this.elements.tags) return;

        this.elements.tags.innerHTML = '';
        this.selectedItems.forEach(item => {
            const tag = document.createElement('span');
            tag.className = 'badge bg-primary text-white me-1 mb-1';
            tag.textContent = item.name || item.label || '';

            const removeBtn = document.createElement('button');
            removeBtn.type = 'button';
            removeBtn.className = 'btn-close btn-close-white ms-1';
            removeBtn.style.fontSize = '0.5rem';
            removeBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.selectItem(item);
            });

            tag.appendChild(removeBtn);
            this.elements.tags.appendChild(tag);
        });
    }

    /**
     * 隐藏下拉框
     */
    hideDropdown() {
        this.showDropdown = false;
        this.selectedIndex = -1;
        this.updateUI();
    }
    
    /**
     * 更新UI
     */
    updateUI() {
        if (!this.isBound) return;

        const { input, clear, dropdown, menu, loading, results, noResults } = this.elements;

        // 更新输入框
        if (input.value !== this.searchText) {
            input.value = this.searchText;
        }
        input.placeholder = this.options.placeholder;

        if (clear) {
            clear.style.display = (this.searchText || this.selectedItems.length > 0) ? 'inline-flex' : 'none';
        }

        // 更新多选标签
        if (this.options.multiSelect && this.elements.tags) {
            this.renderSelectedTags();
        }

        // 更新下拉框显示
        const shouldShow = this.showDropdown || this.isLoading;
        dropdown.classList.toggle('show', shouldShow);
        menu.classList.toggle('show', shouldShow);

        // 更新加载状态
        if (loading) {
            loading.style.display = this.isLoading ? 'block' : 'none';
        }

        // 更新结果
        if (results) {
            results.innerHTML = '';
            this.filteredItems.forEach((item, index) => {
                const button = document.createElement('button');
                button.type = 'button';
                button.className = 'dropdown-item';
                if (index === this.selectedIndex) {
                    button.classList.add('active');
                }

                if (this.options.multiSelect) {
                    const isSelected = this.selectedItems.some(i => i.id === item.id);
                    const checkbox = document.createElement('input');
                    checkbox.type = 'checkbox';
                    checkbox.className = 'form-check-input me-2';
                    checkbox.checked = isSelected;
                    button.appendChild(checkbox);
                }

                const text = document.createTextNode(item.name || item.label || '');
                button.appendChild(text);

                button.addEventListener('click', (e) => {
                    e.preventDefault();
                    this.selectItem(item);
                });

                results.appendChild(button);
            });
        }

        // 更新无结果提示
        if (noResults) {
            const showNoResults = !this.isLoading &&
                                this.filteredItems.length === 0 &&
                                this.searchText.length >= this.options.minQueryLength;
            noResults.style.display = showNoResults ? 'block' : 'none';
        }
    }
    
    /**
     * 设置值
     */
    setValue(text, item = null) {
        this.searchText = text;
        this.selectedItem = item;
        this.updateUI();
    }
    
    /**
     * 获取值
     */
    getValue() {
        if (this.options.multiSelect) {
            return {
                text: this.searchText,
                items: [...this.selectedItems]
            };
        }
        return {
            text: this.searchText,
            item: this.selectedItem
        };
    }

    /**
     * 清空
     */
    clear() {
        this.searchText = '';
        this.selectedItem = null;
        this.selectedItems = [];
        this.filteredItems = [];
        this.hideDropdown();

        if (this.options.onChange) {
            this.options.onChange('', this.options.multiSelect ? [] : null);
        }

        this.updateUI();
    }
    
    /**
     * 销毁
     */
    destroy() {
        clearTimeout(this.debounceTimer);
        this.cache.clear();
        this.isBound = false;
        this.elements = {};
    }
}

/**
 * 创建搜索下拉框实例
 */
export function createSearchDropdown(options) {
    return new SearchDropdown(options);
}

/**
 * 预设配置
 */
export const SearchDropdownPresets = {
    account: (baseUrl = '/api/admin') => ({
        apiUrl: `${baseUrl}/accounts`,
        placeholder: '搜索账户...',
        formatItem: (item) => ({
            id: item.id,
            name: item.remark || item.id,
            data: item
        })
    }),
    
    domain: (baseUrl = '/api/admin') => ({
        apiUrl: `${baseUrl}/domains`,
        placeholder: '搜索域名...',
        formatItem: (item) => ({
            id: item.id,
            name: item.domain || item.id,
            data: item
        })
    }),
    
    server: (baseUrl = '/api/admin') => ({
        apiUrl: `${baseUrl}/servers`,
        placeholder: '搜索服务器...',
        formatItem: (item) => ({
            id: item.id,
            name: `${item.ip}${item.name ? ` (${item.name})` : ''}`,
            data: item
        })
    })
};
