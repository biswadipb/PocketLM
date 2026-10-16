package com.pocketlm.app.data

/** A downloadable GGUF model. */
data class CatalogModel(
    val name: String,
    val description: String,
    val fileName: String,
    val url: String,
    val sizeMb: Int,
    /** Vision models also need this add-on file, downloaded together with the model. */
    val projectorFileName: String? = null,
    val projectorUrl: String? = null,
)

/** Small instruct models that run comfortably on a phone (all Q4_K_M quantised). */
object ModelCatalog {
    val models = listOf(
        CatalogModel(
            name = "Qwen2.5 0.5B Instruct",
            description = "Tiny and fast. Good for testing on any device.",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeMb = 398,
        ),
        CatalogModel(
            name = "SmolVLM 500M (reads images)",
            description = "Tiny vision model: attach a photo and ask about it.",
            fileName = "SmolVLM-500M-Instruct-Q8_0.gguf",
            url = "https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/main/SmolVLM-500M-Instruct-Q8_0.gguf",
            sizeMb = 521,
            projectorFileName = "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf",
            projectorUrl = "https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/main/mmproj-SmolVLM-500M-Instruct-Q8_0.gguf",
        ),
        CatalogModel(
            name = "Llama 3.2 1B Instruct",
            description = "Meta's small general-purpose chat model.",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeMb = 808,
        ),
        CatalogModel(
            name = "Qwen2.5 1.5B Instruct",
            description = "Better quality, still quick on recent phones.",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeMb = 1117,
        ),
        CatalogModel(
            name = "Llama 3.2 3B Instruct",
            description = "Strongest option here. Wants 6 GB+ of RAM.",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeMb = 2020,
        ),
    )
}
