package com.haky.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// 상단 헤더. 표준 TopAppBar(64dp 센터정렬)보다 타이트하게 — 상태바 바로 아래에
// 좌측 정렬 제목 + 우측 액션. iOS 라지 타이틀에 가까운 밀착감.
// 주의: Scaffold가 topBar 슬롯에 상태바 인셋을 이미 적용하므로 여기서 statusBarsPadding을
// 또 넣으면 인셋이 이중으로 들어가 제목 위 공백이 2배가 된다(넣지 말 것).
@Composable
fun CompactHeader(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 8.dp, top = 0.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                lineHeight = MaterialTheme.typography.headlineSmall.fontSize, // 글자 위아래 여분 줄 간격 제거
            ),
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}
