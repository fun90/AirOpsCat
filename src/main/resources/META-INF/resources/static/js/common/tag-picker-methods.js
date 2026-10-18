export const DEFAULT_TAG_COLOR = '#6c757d';

export function createTagEditorState() {
    return { visible: false, id: null, name: '', color: DEFAULT_TAG_COLOR, saving: false };
}

// 需要宿主在 data 中提供 availableTags 与 tagEditor
export function createTagPickerMethods() {
    return {
        toggleTag(model, tagId) {
            const ids = model.tagIds || [];
            model.tagIds = ids.includes(tagId) ? ids.filter(id => id !== tagId) : [...ids, tagId];
        },

        startTagCreate() {
            this.tagEditor = { ...createTagEditorState(), visible: true };
        },

        startTagEdit(tag) {
            this.tagEditor = {
                ...createTagEditorState(),
                visible: true,
                id: tag.id,
                name: tag.name,
                color: tag.color || DEFAULT_TAG_COLOR
            };
        },

        cancelTagEdit() {
            this.tagEditor = createTagEditorState();
        },

        // model 为当前表单对象，新建标签后自动勾选
        async saveTagEditor(model) {
            const editor = this.tagEditor;
            const name = (editor.name || '').trim();
            if (!name) {
                ToastUtils.show('Error', '请输入标签名称', 'danger');
                return;
            }
            editor.saving = true;
            try {
                const isEdit = editor.id != null;
                const existing = isEdit ? this.availableTags.find(tag => tag.id === editor.id) : null;
                const response = await fetch(isEdit ? `/api/admin/tags/${editor.id}` : '/api/admin/tags', {
                    method: isEdit ? 'PUT' : 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        name,
                        color: editor.color,
                        description: existing?.description || null,
                        disabled: existing?.disabled ?? 0
                    })
                });
                if (!response.ok) {
                    throw new Error(response.status === 500 ? '保存失败，标签名称可能已存在' : `保存失败 (${response.status})`);
                }
                const saved = await response.json();
                if (isEdit) {
                    Object.assign(existing, { name: saved.name, color: saved.color });
                } else {
                    this.availableTags.push(saved);
                    this.toggleTag(model, saved.id);
                }
                this.cancelTagEdit();
            } catch (error) {
                ToastUtils.show('Error', error.message, 'danger');
                editor.saving = false;
            }
        }
    };
}
