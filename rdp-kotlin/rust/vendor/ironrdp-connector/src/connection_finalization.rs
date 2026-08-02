use core::mem;

use ironrdp_core::WriteBuf;
use ironrdp_pdu::PduHint;
use ironrdp_pdu::rdp::capability_sets::SERVER_CHANNEL_ID;
use ironrdp_pdu::rdp::headers::ShareDataPdu;
use ironrdp_pdu::rdp::{finalization_messages, server_error_info};
use tracing::{debug, warn};

use crate::{
    ConnectorError, ConnectorErrorExt as _, ConnectorResult, Sequence, State, Written, general_err, reason_err,
};

#[derive(Default, Debug, Copy, Clone)]
#[non_exhaustive]
pub enum ConnectionFinalizationState {
    #[default]
    Consumed,

    SendSynchronize,
    SendControlCooperate,
    SendRequestControl,
    SendFontList,

    WaitForResponse,

    Finished,
}

impl State for ConnectionFinalizationState {
    fn name(&self) -> &'static str {
        match self {
            Self::Consumed => "Consumed",
            Self::SendSynchronize => "SendSynchronize",
            Self::SendControlCooperate => "SendControlCooperate",
            Self::SendRequestControl => "SendRequestControl",
            Self::SendFontList => "SendFontList",
            Self::WaitForResponse => "WaitForResponse",
            Self::Finished => "Finished",
        }
    }

    fn is_terminal(&self) -> bool {
        matches!(self, Self::Finished)
    }

    fn as_any(&self) -> &dyn core::any::Any {
        self
    }
}

#[derive(Debug, Copy, Clone)]
pub struct ConnectionFinalizationSequence {
    pub state: ConnectionFinalizationState,
    pub io_channel_id: u16,
    pub user_channel_id: u16,
    pub share_id: u32,
}

impl ConnectionFinalizationSequence {
    pub fn new(io_channel_id: u16, user_channel_id: u16, share_id: u32) -> Self {
        Self {
            state: ConnectionFinalizationState::SendSynchronize,
            io_channel_id,
            user_channel_id,
            share_id,
        }
    }
}

impl Sequence for ConnectionFinalizationSequence {
    fn next_pdu_hint(&self) -> Option<&dyn PduHint> {
        match self.state {
            ConnectionFinalizationState::Consumed => None,
            ConnectionFinalizationState::SendSynchronize => None,
            ConnectionFinalizationState::SendControlCooperate => None,
            ConnectionFinalizationState::SendRequestControl => None,
            ConnectionFinalizationState::SendFontList => None,
            ConnectionFinalizationState::WaitForResponse => Some(&ironrdp_pdu::X224_HINT),
            ConnectionFinalizationState::Finished => None,
        }
    }

    fn state(&self) -> &dyn State {
        &self.state
    }

    fn step(&mut self, input: &[u8], output: &mut WriteBuf) -> ConnectorResult<Written> {
        let (written, next_state) = match mem::take(&mut self.state) {
            ConnectionFinalizationState::Consumed => {
                return Err(general_err!(
                    "connection finalization sequence state is consumed (this is a bug)",
                ));
            }

            ConnectionFinalizationState::SendSynchronize => {
                let message = ShareDataPdu::Synchronize(finalization_messages::SynchronizePdu {
                    target_user_id: self.user_channel_id,
                });

                debug!(?message, "Send");

                let written = ironrdp_pdu::rdp::headers::encode_share_data(
                    self.user_channel_id,
                    self.io_channel_id,
                    self.share_id,
                    message,
                    output,
                )
                .map_err(ConnectorError::encode)?;

                (
                    Written::from_size(written)?,
                    ConnectionFinalizationState::SendControlCooperate,
                )
            }

            ConnectionFinalizationState::SendControlCooperate => {
                let message = ShareDataPdu::Control(finalization_messages::ControlPdu {
                    action: finalization_messages::ControlAction::Cooperate,
                    grant_id: 0,
                    control_id: 0,
                });

                debug!(?message, "Send");

                let written = ironrdp_pdu::rdp::headers::encode_share_data(
                    self.user_channel_id,
                    self.io_channel_id,
                    self.share_id,
                    message,
                    output,
                )
                .map_err(ConnectorError::encode)?;

                (
                    Written::from_size(written)?,
                    ConnectionFinalizationState::SendRequestControl,
                )
            }

            ConnectionFinalizationState::SendRequestControl => {
                let message = ShareDataPdu::Control(finalization_messages::ControlPdu {
                    action: finalization_messages::ControlAction::RequestControl,
                    grant_id: 0,
                    control_id: 0,
                });

                debug!(?message, "Send");

                let written = ironrdp_pdu::rdp::headers::encode_share_data(
                    self.user_channel_id,
                    self.io_channel_id,
                    self.share_id,
                    message,
                    output,
                )
                .map_err(ConnectorError::encode)?;

                (Written::from_size(written)?, ConnectionFinalizationState::SendFontList)
            }

            ConnectionFinalizationState::SendFontList => {
                let message = ShareDataPdu::FontList(finalization_messages::FontPdu::default());

                debug!(?message, "Send");

                let written = ironrdp_pdu::rdp::headers::encode_share_data(
                    self.user_channel_id,
                    self.io_channel_id,
                    self.share_id,
                    message,
                    output,
                )
                .map_err(ConnectorError::encode)?;

                (
                    Written::from_size(written)?,
                    ConnectionFinalizationState::WaitForResponse,
                )
            }

            ConnectionFinalizationState::WaitForResponse => {
                let sdi = ironrdp_pdu::mcs::decode_send_data_indication(input).map_err(ConnectorError::decode)?;
                let user_data = sdi.user_data;
                let ctx = match ironrdp_pdu::rdp::headers::decode_share_data(sdi) {
                    Ok(ctx) => ctx,
                    // Some servers (VirtualBox VRDP, Haven #422) send the Server
                    // Font Map with an empty FontPdu body that ironrdp-pdu's
                    // strict decode rejects (NotEnoughBytes). The Font Map only
                    // signals finalization-complete and its contents are unused,
                    // so a structurally-recognisable-but-short Font Map is
                    // accepted as received rather than aborting the connect.
                    Err(e) if is_server_font_map(user_data) => {
                        warn!("Server Font Map failed strict decode ({e}); accepting as finalization-complete (lenient FontMap, #422)");
                        self.state = ConnectionFinalizationState::Finished;
                        return Ok(Written::Nothing);
                    }
                    Err(e) => return Err(ConnectorError::decode(e)),
                };

                debug!(message = ?ctx.pdu, "Received");

                let next_state = match ctx.pdu {
                    ShareDataPdu::Synchronize(_) => {
                        debug!("Server Synchronize");
                        ConnectionFinalizationState::WaitForResponse
                    }
                    ShareDataPdu::Control(control_pdu) => match control_pdu.action {
                        finalization_messages::ControlAction::Cooperate => {
                            if control_pdu.grant_id == 0 && control_pdu.control_id == 0 {
                                debug!("Server Control (Cooperate)");
                            } else {
                                warn!(
                                    control_pdu.grant_id,
                                    control_pdu.control_id,
                                    user_channel_id = self.user_channel_id,
                                    "Server Control (Cooperate) has non-zero grant_id or control_id",
                                );
                            }
                            ConnectionFinalizationState::WaitForResponse
                        }
                        finalization_messages::ControlAction::GrantedControl => {
                            debug!(
                                control_pdu.grant_id,
                                control_pdu.control_id,
                                user_channel_id = self.user_channel_id,
                                SERVER_CHANNEL_ID
                            );

                            if control_pdu.grant_id != self.user_channel_id {
                                warn!(
                                    "Server Control (Granted Control) had invalid grant_id, expected {}, but got {}",
                                    self.user_channel_id, control_pdu.grant_id
                                );
                            }

                            if control_pdu.control_id != u32::from(SERVER_CHANNEL_ID) {
                                warn!(
                                    "Server Control (Granted Control) had invalid control_id, expected {}, but got {}",
                                    SERVER_CHANNEL_ID, control_pdu.control_id
                                );
                            }

                            ConnectionFinalizationState::WaitForResponse
                        }
                        _ => return Err(general_err!("unexpected control action")),
                    },
                    ShareDataPdu::ServerSetErrorInfo(server_error_info::ServerSetErrorInfoPdu(error_info)) => {
                        match error_info {
                            server_error_info::ErrorInfo::ProtocolIndependentCode(
                                server_error_info::ProtocolIndependentCode::None,
                            ) => ConnectionFinalizationState::WaitForResponse,
                            _ => {
                                return Err(reason_err!(
                                    "ServerSetErrorInfo",
                                    "server returned error info: {}",
                                    error_info.description()
                                ));
                            }
                        }
                    }
                    ShareDataPdu::FontMap(_) => {
                        // https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-rdpbcgr/023f1e69-cfe8-4ee6-9ee0-7e759fb4e4ee
                        //
                        // Once the client has sent the Confirm Active PDU, it can start
                        // sending mouse and keyboard input to the server, and upon receipt
                        // of the Font List PDU the server can start sending graphics
                        // output to the client.

                        ConnectionFinalizationState::Finished
                    }
                    _ => return Err(general_err!("unexpected server message")),
                };

                (Written::Nothing, next_state)
            }

            ConnectionFinalizationState::Finished => return Err(general_err!("finalization already finished")),
        };

        self.state = next_state;

        Ok(written)
    }
}

/// Peek whether this Send Data Indication's user data is a Server Font Map PDU,
/// without decoding the FontPdu body.
///
/// VirtualBox's VRDP server sends the Server Font Map with an empty (0-byte)
/// FontPdu body, where `ironrdp-pdu`'s `FontPdu::decode` requires the full 8
/// bytes and fails with `NotEnoughBytes` — killing the connect right after auth
/// (Haven #422). The Font Map's contents are ignored by the finalization
/// sequence anyway (MS-RDPBCGR 1.3.1.1), so a short/empty one is accepted
/// rather than aborting — matching mstsc/FreeRDP behaviour.
///
/// Lived in `legacy.rs` until that module was removed upstream in 0.10.0.
///
/// `pduType2` sits at offset 14: a 6-byte Share Control header then 8 bytes into
/// the Share Data header. MS-RDPBCGR 2.2.8.1.1.1.1 / 2.2.8.1.1.1.2.
fn is_server_font_map(user_data: &[u8]) -> bool {
    const PDUTYPE_DATAPDU: u16 = 0x7;
    const PDUTYPE2_FONTMAP: u8 = 0x28;
    if user_data.len() < 15 {
        return false;
    }
    let pdu_type = u16::from_le_bytes([user_data[2], user_data[3]]);
    (pdu_type & 0x0F) == PDUTYPE_DATAPDU && user_data[14] == PDUTYPE2_FONTMAP
}

#[cfg(test)]
mod font_map_tests {
    use super::is_server_font_map;

    #[test]
    fn recognises_a_font_map_header() {
        let mut pdu = vec![0u8; 15];
        pdu[2] = 0x17; // pduType low nibble = PDUTYPE_DATAPDU
        pdu[14] = 0x28; // PDUTYPE2_FONTMAP
        assert!(is_server_font_map(&pdu));
    }

    #[test]
    fn rejects_other_pdus_and_short_buffers() {
        let mut pdu = vec![0u8; 15];
        pdu[2] = 0x17;
        pdu[14] = 0x1F; // some other pduType2
        assert!(!is_server_font_map(&pdu));
        assert!(!is_server_font_map(&[]));
        assert!(!is_server_font_map(&vec![0u8; 14]));
    }
}
