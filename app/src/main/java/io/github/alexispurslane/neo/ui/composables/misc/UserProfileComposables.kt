package io.github.alexispurslane.neo.ui.composables.misc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexispurslane.neo.data.models.User
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.Presence


@Composable
fun UserCard(
    modifier: Modifier = Modifier,
    userProfile: User,
    client: MatrixClient?
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box {
            UserRow(
                modifier = Modifier.padding(30.dp),
                userProfile = userProfile,
                client = client
            )
        }
    }
}

@Composable
fun UserRow(
    modifier: Modifier = Modifier,
    iconSize: Dp = 64.dp,
    userProfile: User,
    client: MatrixClient?
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (userProfile.avatarUrl != null) {
            UserAvatar(
                size = iconSize,
                user = userProfile,
                client = client
            )
        }
        Column {
            if (userProfile.displayName != null) {
                Text(
                    userProfile.displayName,
                    fontSize = (iconSize.value / 2 - 4).sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Start
                )
                Text(
                    userProfile.userId.full,
                    fontSize = (iconSize.value / 4 - 4).sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Start,
                    color = Color.LightGray
                )
            } else {
                Text(
                    "@${userProfile.userId.localpart}",
                    fontSize = (iconSize.value / 2 - 4).sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Start
                )
                Text(
                    userProfile.userId.domain,
                    fontSize = (iconSize.value / 4 - 4).sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Start,
                    color = Color.LightGray
                )
            }
        }
    }
}

@Composable
fun UserAvatar(
    modifier: Modifier = Modifier,
    size: Dp,
    user: User,
    client: MatrixClient?,
    onClick: (UserId) -> Unit = {}
) {
    Box {
        if (user.avatarUrl != null && client != null) {
            MatrixImage(
                modifier = modifier
                    .size(size)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .clickable { onClick(user.userId) },
                mxcUri = user.avatarUrl,
                client = client
            )
        } else {
            Box(
                modifier = modifier
                    .size(size)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(Color.LightGray)
                    .clickable { onClick(user.userId) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "No Avatar User Icon"
                )
            }
        }

        val color =
            when (user.presence) {
                Presence.ONLINE -> Color(0xFF3ABF7E)
                Presence.UNAVAILABLE -> Color.Red
                Presence.OFFLINE -> Color.LightGray
            }

        val statusSize = (size / 3)
        val statusOffset = (size / 2 - statusSize / 2 - 1.dp)
        Box(
            modifier = Modifier
                .offset(x = statusOffset, y = statusOffset)
                .size(statusSize)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(color)
                .align(Alignment.Center)
        )
    }
}