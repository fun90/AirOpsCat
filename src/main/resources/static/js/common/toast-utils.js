/**
 * Utility functions for working with toasts in Tabler UI 1.2.0
 * This version uses ES6 modules and Bootstrap 5's native toast API
 */

/**
 * Import the Toast class from Tabler UI's bundled version of Bootstrap
 * Note: The actual path may need to be adjusted based on your project structure
 */
import { Toast } from '/static/tabler/js/tabler.esm.min.js';

const ToastUtils = {
    /**
     * 显示一个 Toast 通知
     * @param {string} title - Toast 标题
     * @param {string} message - Toast 消息内容
     * @param {string} type - Toast 类型 (success, danger, warning, info)
     * @param {number} [delay=3000] - 自动隐藏延迟(毫秒)
     */
    show(title, message, type, delay = 3000) {
        // 如果已存在 toast 容器则使用，否则创建新的
        let toastContainer = document.getElementById('toast-container');
        if (!toastContainer) {
            toastContainer = document.createElement('div');
            toastContainer.id = 'toast-container';
            toastContainer.className = 'toast-container position-fixed top-0 end-0 p-3';
            document.body.appendChild(toastContainer);
        }

        // 创建新的 toast 元素
        const toastEl = document.createElement('div');
        toastEl.className = `toast align-items-center text-white border-0 bg-${type}`;
        toastEl.innerHTML = `
            <div class="d-flex">
                <div class="toast-body">
                    <i class="fas fa-check-circle"></i> ${message || '操作成功'}
                </div>
                <button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button>
            </div>
        `;

        // 添加到容器
        toastContainer.appendChild(toastEl);

        // 初始化 toast 并显示
        const toast = new Toast(toastEl, {
            delay: delay,
            autohide: true
        });

        // 使用 show() API 显示 toast
        toast.show();

        // 监听关闭事件，移除DOM元素
        toastEl.addEventListener('hidden.bs.toast', () => {
            toastEl.remove();
        });
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