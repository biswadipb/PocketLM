package com.pocketlm.app.data

/** Chat templates built into llama.cpp that can be chosen for a model whose own template is missing or wrong. */
data class ChatTemplateChoice(val id: String, val label: String)

object ChatTemplates {
    /** The empty id means "use the template inside the model". */
    val choices = listOf(
        ChatTemplateChoice("", "Automatic (the model's own)"),
        ChatTemplateChoice("chatml", "ChatML (Qwen, many others)"),
        ChatTemplateChoice("llama3", "Llama 3"),
        ChatTemplateChoice("gemma", "Gemma"),
        ChatTemplateChoice("mistral-v1", "Mistral [INST]"),
        ChatTemplateChoice("phi3", "Phi-3"),
        ChatTemplateChoice("phi4", "Phi-4"),
        ChatTemplateChoice("zephyr", "Zephyr"),
        ChatTemplateChoice("llama2", "Llama 2"),
        ChatTemplateChoice("deepseek3", "DeepSeek V3"),
        ChatTemplateChoice("vicuna", "Vicuna"),
    )

    fun label(id: String): String = choices.firstOrNull { it.id == id }?.label ?: id.ifEmpty { choices.first().label }
}
