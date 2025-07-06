/**
 * Modal Autosize Handler
 * 简化版本 - 专门处理 Modal 中的 autosize 功能
 */

class ModalAutosizeHandler {
    constructor() {
        this.initEventListeners();
    }
    
    initEventListeners() {
        // 监听 Modal 显示事件
        document.addEventListener('shown.bs.modal', (event) => {
            this.initAutosizeInContainer(event.target);
        });
        
        // 监听 Tab 切换事件
        document.addEventListener('shown.bs.tab', (event) => {
            const tabPaneId = event.target.getAttribute('href') || event.target.getAttribute('data-bs-target');
            if (tabPaneId) {
                const tabPane = document.querySelector(tabPaneId);
                if (tabPane) {
                    this.initAutosizeInContainer(tabPane);
                }
            }
        });
        
        // 监听 Modal 隐藏事件，清理 autosize
        document.addEventListener('hidden.bs.modal', (event) => {
            this.destroyAutosizeInContainer(event.target);
        });
    }
    
    initAutosizeInContainer(container) {
        if (typeof autosize === 'undefined') {
            return;
        }
        
        const textareas = container.querySelectorAll('textarea[data-bs-toggle="autosize"]');
        
        if (textareas.length === 0) {
            return;
        }
        
        textareas.forEach(textarea => {
            // 销毁已存在的 autosize 实例
            if (textarea.hasAttribute('data-autosize-initialized')) {
                autosize.destroy(textarea);
            }
            
            // 初始化新的 autosize 实例
            autosize(textarea);
            textarea.setAttribute('data-autosize-initialized', 'true');
            
            // 如果 textarea 已经有内容，立即更新高度
            if (textarea.value) {
                autosize.update(textarea);
            }
        });
    }
    
    destroyAutosizeInContainer(container) {
        const textareas = container.querySelectorAll('textarea[data-bs-toggle="autosize"]');
        textareas.forEach(textarea => {
            if (typeof autosize !== 'undefined' && textarea.hasAttribute('data-autosize-initialized')) {
                autosize.destroy(textarea);
                textarea.removeAttribute('data-autosize-initialized');
            }
        });
    }
    
    // 手动初始化方法
    manualInit() {
        const activeModal = document.querySelector('.modal.show');
        if (activeModal) {
            this.initAutosizeInContainer(activeModal);
        }
    }
}

// 初始化处理器
document.addEventListener('DOMContentLoaded', () => {
    const handler = new ModalAutosizeHandler();
    
    // 添加全局方法
    window.modalAutosizeHandler = handler;
    window.manualInitAutosize = () => handler.manualInit();
});

export default ModalAutosizeHandler; 