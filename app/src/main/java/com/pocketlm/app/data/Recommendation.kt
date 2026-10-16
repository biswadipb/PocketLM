package com.pocketlm.app.data

object Recommendation {

    /**
     * The catalog model to suggest on this phone: the largest text model that should fit comfortably, since
     * a bigger model answers better. Vision models come with an add-on file and are left out of the pick.
     */
    fun pick(models: List<CatalogModel>, levelOf: (CatalogModel) -> MemoryEstimate.Level): CatalogModel? =
        models.filter { it.projectorFileName == null && levelOf(it) == MemoryEstimate.Level.Comfortable }
            .maxByOrNull { it.sizeMb }
}
