/**
 * Field Tree Builder
 * Builds hierarchical tree view HTML for field selection
 *
 * @module FieldTreeBuilder
 */

import type { FieldDefinition } from '../types/index.js';

interface TreeNode {
    children: Record<string, TreeNode>;
    fields: FieldDefinition[];
}

export class FieldTreeBuilder {
    /**
     * Build a tree view HTML from flat field list
     */
    static buildTree(fields: FieldDefinition[], selectedFields: Set<string>): string {
        if (!fields || fields.length === 0) {
            return '<p class="hint" style="color: orange;">No fields available</p>';
        }

        const hierarchy = this.buildHierarchy(fields);
        return this.renderNode(hierarchy, 0, selectedFields);
    }

    /**
     * Build hierarchical structure from flat field list
     */
    private static buildHierarchy(fields: FieldDefinition[]): TreeNode {
        const root: TreeNode = { children: {}, fields: [] };

        for (const field of fields) {
            const parts = field.path.split('.');
            let current = root;

            // Navigate/create hierarchy
            for (let i = 0; i < parts.length - 1; i++) {
                const part = parts[i];
                if (!current.children[part]) {
                    current.children[part] = { children: {}, fields: [] };
                }
                current = current.children[part];
            }

            // Add field at final level
            current.fields.push(field);
        }

        return root;
    }

    /**
     * Render a node in the tree
     */
    private static renderNode(node: TreeNode, depth: number, selectedFields: Set<string>): string {
        let html = '';

        // Render fields at this level
        for (const field of node.fields) {
            html += this.renderField(field, depth, selectedFields);
        }

        // Render child nodes recursively
        for (const [, child] of Object.entries(node.children)) {
            if (child.fields.length > 0 || Object.keys(child.children).length > 0) {
                html += this.renderNode(child, depth + 1, selectedFields);
            }
        }

        return html;
    }

    /**
     * Render a single field item
     */
    private static renderField(field: FieldDefinition, depth: number, selectedFields: Set<string>): string {
        const checked = selectedFields.has(field.path) ? 'checked' : '';
        const nestedClass = depth > 0 ? `nested nested-${Math.min(depth, 3)}` : '';

        return `
            <div class="field-item ${nestedClass}">
                <input type="checkbox" 
                       class="field-checkbox" 
                       data-field-path="${field.path}" 
                       ${checked}>
                <span class="field-label">${this.escapeHtml(field.label)}</span>
                <span class="field-type">[${this.escapeHtml(field.type)}]</span>
            </div>
        `;
    }

    /**
     * Escape HTML special characters
     */
    private static escapeHtml(text: string): string {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    /**
     * Get field tree CSS styles
     */
    static getStyles(): string {
        return `
            .field-selector {
                background: rgba(0, 0, 0, 0.02);
                padding: 0.75rem;
                border: 1px solid #7a7971;
                border-radius: 4px;
            }

            .field-tree {
                max-height: 300px;
                overflow-y: auto;
                background: white;
                border: 1px solid #ccc;
                padding: 0.5rem;
                margin-top: 0.5rem;
            }

            .field-item {
                padding: 0.25rem 0;
                display: flex;
                align-items: center;
            }

            .field-item input[type="checkbox"] {
                width: auto;
                margin-right: 0.5rem;
            }

            .field-item .field-label {
                flex: 1;
            }

            .field-item .field-type {
                font-size: 0.8rem;
                color: #666;
                margin-left: 0.5rem;
            }

            .field-item.nested {
                padding-left: 1.5rem;
            }

            .field-item.nested-2 {
                padding-left: 3rem;
            }

            .field-item.nested-3 {
                padding-left: 4.5rem;
            }
        `;
    }
}

