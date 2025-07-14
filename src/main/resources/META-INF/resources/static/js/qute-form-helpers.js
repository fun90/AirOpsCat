/**
 * Qute表单辅助工具
 * 提供表单绑定、验证等功能，替代Thymeleaf的表单处理
 */

class QuteFormHelper {
    constructor() {
        this.validationErrors = {};
        this.formData = {};
    }

    /**
     * 绑定表单数据到Vue实例
     */
    bindFormData(form, vueInstance) {
        const formData = new FormData(form);
        const data = {};
        
        for (let [key, value] of formData.entries()) {
            // 处理多选框
            if (form.querySelector(`input[name="${key}"][type="checkbox"]`)) {
                if (!data[key]) data[key] = [];
                data[key].push(value);
            }
            // 处理单选框和其他输入
            else {
                data[key] = value;
            }
        }
        
        // 更新Vue实例数据
        Object.assign(vueInstance, data);
        return data;
    }

    /**
     * 验证表单字段
     */
    validateField(fieldName, value, rules) {
        const errors = [];
        
        if (rules.required && (!value || value.toString().trim() === '')) {
            errors.push(`${fieldName} 是必填字段`);
        }
        
        if (rules.minLength && value && value.length < rules.minLength) {
            errors.push(`${fieldName} 最少需要 ${rules.minLength} 个字符`);
        }
        
        if (rules.maxLength && value && value.length > rules.maxLength) {
            errors.push(`${fieldName} 最多允许 ${rules.maxLength} 个字符`);
        }
        
        if (rules.pattern && value && !rules.pattern.test(value)) {
            errors.push(`${fieldName} 格式不正确`);
        }
        
        if (rules.email && value && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
            errors.push(`${fieldName} 邮箱格式不正确`);
        }
        
        if (rules.min && value && parseFloat(value) < rules.min) {
            errors.push(`${fieldName} 不能小于 ${rules.min}`);
        }
        
        if (rules.max && value && parseFloat(value) > rules.max) {
            errors.push(`${fieldName} 不能大于 ${rules.max}`);
        }
        
        return errors;
    }

    /**
     * 验证整个表单
     */
    validateForm(formData, validationRules) {
        const errors = {};
        
        Object.keys(validationRules).forEach(fieldName => {
            const fieldErrors = this.validateField(
                fieldName, 
                formData[fieldName], 
                validationRules[fieldName]
            );
            
            if (fieldErrors.length > 0) {
                errors[fieldName] = fieldErrors[0]; // 只显示第一个错误
            }
        });
        
        this.validationErrors = errors;
        return Object.keys(errors).length === 0;
    }

    /**
     * 设置表单字段错误
     */
    setFieldError(fieldName, error) {
        this.validationErrors[fieldName] = error;
    }

    /**
     * 清除表单字段错误
     */
    clearFieldError(fieldName) {
        delete this.validationErrors[fieldName];
    }

    /**
     * 清除所有表单错误
     */
    clearAllErrors() {
        this.validationErrors = {};
    }

    /**
     * 获取字段错误信息
     */
    getFieldError(fieldName) {
        return this.validationErrors[fieldName];
    }

    /**
     * 检查字段是否有错误
     */
    hasFieldError(fieldName) {
        return !!this.validationErrors[fieldName];
    }

    /**
     * 序列化表单数据为JSON
     */
    serializeForm(form) {
        const formData = new FormData(form);
        const data = {};
        
        for (let [key, value] of formData.entries()) {
            // 处理文件上传
            if (value instanceof File) {
                data[key] = value;
            }
            // 处理多选框
            else if (form.querySelector(`input[name="${key}"][type="checkbox"]`)) {
                if (!data[key]) data[key] = [];
                data[key].push(value);
            }
            // 处理数字类型
            else if (form.querySelector(`input[name="${key}"][type="number"]`)) {
                data[key] = value ? parseFloat(value) : null;
            }
            // 处理布尔类型
            else if (form.querySelector(`input[name="${key}"][type="checkbox"]:not([value])`)) {
                data[key] = form.querySelector(`input[name="${key}"]`).checked;
            }
            // 处理日期时间
            else if (form.querySelector(`input[name="${key}"][type="datetime-local"]`)) {
                data[key] = value ? new Date(value).toISOString() : null;
            }
            // 处理普通文本
            else {
                data[key] = value;
            }
        }
        
        return data;
    }

    /**
     * 填充表单数据
     */
    populateForm(form, data) {
        Object.keys(data).forEach(key => {
            const field = form.querySelector(`[name="${key}"]`);
            if (!field) return;
            
            const value = data[key];
            
            // 处理复选框
            if (field.type === 'checkbox') {
                if (Array.isArray(value)) {
                    // 多选框
                    form.querySelectorAll(`input[name="${key}"][type="checkbox"]`).forEach(cb => {
                        cb.checked = value.includes(cb.value);
                    });
                } else {
                    // 单个复选框
                    field.checked = !!value;
                }
            }
            // 处理单选框
            else if (field.type === 'radio') {
                form.querySelectorAll(`input[name="${key}"][type="radio"]`).forEach(radio => {
                    radio.checked = radio.value === value;
                });
            }
            // 处理日期时间
            else if (field.type === 'datetime-local' && value) {
                const date = new Date(value);
                field.value = date.toISOString().slice(0, 16);
            }
            // 处理普通输入
            else {
                field.value = value || '';
            }
        });
    }

    /**
     * 重置表单
     */
    resetForm(form) {
        form.reset();
        this.clearAllErrors();
    }

    /**
     * 显示表单加载状态
     */
    setFormLoading(form, loading) {
        const submitBtn = form.querySelector('button[type="submit"]');
        const inputs = form.querySelectorAll('input, select, textarea');
        
        if (loading) {
            submitBtn?.setAttribute('disabled', 'disabled');
            submitBtn?.classList.add('btn-loading');
            inputs.forEach(input => input.setAttribute('disabled', 'disabled'));
        } else {
            submitBtn?.removeAttribute('disabled');
            submitBtn?.classList.remove('btn-loading');
            inputs.forEach(input => input.removeAttribute('disabled'));
        }
    }
}

/**
 * Vue.js集成的表单验证混入
 */
const QuteFormMixin = {
    data() {
        return {
            validationErrors: {},
            formHelper: new QuteFormHelper()
        };
    },
    
    methods: {
        /**
         * 验证单个字段
         */
        validateField(fieldName, value, rules) {
            const errors = this.formHelper.validateField(fieldName, value, rules);
            
            if (errors.length > 0) {
                this.$set(this.validationErrors, fieldName, errors[0]);
                return false;
            } else {
                this.$delete(this.validationErrors, fieldName);
                return true;
            }
        },

        /**
         * 验证表单
         */
        validateForm(data, rules) {
            const isValid = this.formHelper.validateForm(data, rules);
            this.validationErrors = { ...this.formHelper.validationErrors };
            return isValid;
        },

        /**
         * 设置服务器验证错误
         */
        setServerErrors(errors) {
            this.validationErrors = { ...errors };
        },

        /**
         * 清除验证错误
         */
        clearValidationErrors() {
            this.validationErrors = {};
            this.formHelper.clearAllErrors();
        },

        /**
         * 检查字段是否有错误
         */
        hasError(fieldName) {
            return !!this.validationErrors[fieldName];
        },

        /**
         * 获取字段错误信息
         */
        getError(fieldName) {
            return this.validationErrors[fieldName];
        }
    }
};

// 导出为全局变量
window.QuteFormHelper = QuteFormHelper;
window.QuteFormMixin = QuteFormMixin;