package com.sephilabs.sharedledger.common

data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
) {
    companion object {
        fun <T> of(items: List<T>, page: Int, size: Int, total: Long): PageResponse<T> =
            PageResponse(items, page, size, total)
    }
}
