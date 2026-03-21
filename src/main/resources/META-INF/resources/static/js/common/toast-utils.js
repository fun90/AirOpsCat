/**
 * Utility functions for working with toasts in Tabler UI 1.3.2
 * This version uses ES6 modules and Bootstrap 5's native toast API
 */

/**
 * Import the Toast class from Tabler UI's bundled version of Bootstrap
 * Note: The actual path may need to be adjusted based on your project structure
 */
import { Toast } from '/static/tabler/js/tabler.esm.min.js';

const ToastUtils = {
    createContainer() {
        let toastContainer = document.getElementById('toast-container');
        if (!toastContainer) {
            toastContainer = document.createElement('div');
            toastContainer.id = 'toast-container';
            toastContainer.className = 'toast-container position-fixed top-0 end-0 p-3';
            document.body.appendChild(toastContainer);
        }
        return toastContainer;
    },

    createToastElement(title, message, type, options = {}) {
        const {
            showSpinner = false,
            closable = true
        } = options;

        const toastEl = document.createElement('div');
        toastEl.className = `toast align-items-center text-white border-0 bg-${type}`;
        toastEl.setAttribute('role', 'alert');
        toastEl.setAttribute('aria-live', 'assertive');
        toastEl.setAttribute('aria-atomic', 'true');
        toastEl.innerHTML = `
            <div class="d-flex align-items-center">
                <div class="toast-body d-flex align-items-center gap-2">
                    ${showSpinner ? '<span class="spinner-border spinner-border-sm flex-shrink-0" role="status" aria-hidden="true"></span>' : ''}
                    <div class="d-flex flex-column">
                        ${title ? `<strong>${title}</strong>` : ''}
                        <span>${message || '操作成功'}</span>
                    </div>
                </div>
                ${closable ? '<button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button>' : ''}
            </div>
        `;

        toastEl.addEventListener('hidden.bs.toast', () => {
            toastEl.remove();
        });

        return toastEl;
    },

    /**
     * 显示一个 Toast 通知
     * @param {string} title - Toast 标题
     * @param {string} message - Toast 消息内容
     * @param {string} type - Toast 类型 (success, danger, warning, info)
     * @param {number} [delay=3000] - 自动隐藏延迟(毫秒)
     */
    show(title, message, type, delay = 3000) {
        const toastContainer = this.createContainer();
        const toastEl = this.createToastElement(title, message, type);
        toastContainer.appendChild(toastEl);

        const toast = new Toast(toastEl, {
            delay: delay,
            autohide: true
        });

        toast.show();
        return toast;
    },

    loading(title, message) {
        const toastContainer = this.createContainer();
        const toastEl = this.createToastElement(title, message, 'info', {
            showSpinner: true,
            closable: false
        });
        toastContainer.appendChild(toastEl);

        const toast = new Toast(toastEl, {
            autohide: false
        });

        toast.show();

        return {
            hide() {
                toast.hide();
            }
        };
    },

    /**
     * 显示成功 Toast
     * @param {string} message - Toast 消息内容
     * @param {string} [title='Success'] - Toast 标题
     */
    success(message, title = 'Success') {
        this.show(title, message, 'success');
    },

    /**
     * 显示错误 Toast
     * @param {string} message - Toast 消息内容
     * @param {string} [title='Error'] - Toast 标题
     */
    error(message, title = 'Error') {
        this.show(title, message, 'danger');
    },

    /**
     * 显示警告 Toast
     * @param {string} message - Toast 消息内容
     * @param {string} [title='Warning'] - Toast 标题
     */
    warning(message, title = 'Warning') {
        this.show(title, message, 'warning');
    },

    /**
     * 显示信息 Toast
     * @param {string} message - Toast 消息内容
     * @param {string} [title='Info'] - Toast 标题
     */
    info(message, title = 'Info') {
        this.show(title, message, 'info');
    },

    /**
     * 移除所有 Toast
     */
    clear() {
        const container = document.getElementById('toast-container');
        if (container) {
            const toasts = container.querySelectorAll('.toast');
            toasts.forEach(toastEl => {
                const toast = Toast.getInstance(toastEl);
                if (toast) {
                    toast.hide();
                }
            });
        }
    }
};

// 导出为全局对象，以便在任何地方使用
window.ToastUtils = ToastUtils;
// export default ToastUtils;
