package com.gtstore

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AccessRequestsOverlay(
    modifier: Modifier = Modifier
) {
    val requests = AccessManager.pendingRequests

    AnimatedVisibility(
        visible = requests.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
            border = BorderStroke(1.dp, Color(0xFF0070CC))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SOLICITAÇÕES DE ACESSO PS4",
                        color = Color(0xFF64B5F6),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "${requests.size} PENDENTE(S)",
                        color = Color(0xFFFFB300),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                requests.forEach { req ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                        border = BorderStroke(1.dp, Color(0xFF2A2A2A))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = req.gameTitle,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    text = "Console: ${req.consoleId} • ${req.gameKey}",
                                    color = Color(0xFFAAAAAA),
                                    fontSize = 11.sp
                                )
                                if (req.clientIp.isNotBlank()) {
                                    Text(
                                        text = "IP: ${req.clientIp}",
                                        color = Color(0xFF777777),
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { AccessManager.approveAccess(req.consoleId, req.gameKey) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2E7D32),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("APROVAR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { AccessManager.rejectAccess(req.consoleId, req.gameKey) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFC62828),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("RECUSAR", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
