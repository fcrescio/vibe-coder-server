from __future__ import annotations

import base64
from collections.abc import AsyncGenerator
from typing import ClassVar

from acp.schema import ContentToolCallContent, TextContentBlock, ToolCallProgress
from pydantic import BaseModel, Field

from vibe.acp.tools.base import AcpToolState, BaseAcpTool
from vibe.acp.tools.session_update import resolve_kind
from vibe.core.tools.base import BaseToolConfig, InvokeContext, ToolError
from vibe.core.tools.ui import ToolCallDisplay, ToolResultDisplay, ToolUIData
from vibe.core.types import ToolResultEvent, ToolStreamEvent


class _DeviceConfig(BaseToolConfig):
    pass


class _DeviceState(AcpToolState):
    pass


class _DeviceTool(BaseAcpTool[_DeviceState]):
    state: _DeviceState

    @classmethod
    def _get_tool_config_class(cls) -> type[_DeviceConfig]:
        return _DeviceConfig

    @classmethod
    def _get_tool_state_class(cls) -> type[_DeviceState]:
        return _DeviceState

    async def _call_device(self, method: str, params: dict) -> dict:
        client, _session_id = self._load_state()
        return await client.ext_method(method, params)


class DeviceScreencapArgs(BaseModel):
    serial: str | None = Field(
        default=None,
        description="ADB device serial. If omitted, the first connected device is used.",
    )


class DeviceScreencapResult(BaseModel):
    serial: str
    mime_type: str = "image/png"
    data: str = Field(
        exclude=True,
        description="Base64 PNG payload. Excluded from the LLM text result but sent to the UI.",
    )
    message: str


class DeviceScreencap(
    _DeviceTool,
    ToolUIData[DeviceScreencapArgs, DeviceScreencapResult],
):
    description: ClassVar[str] = (
        "Capture a PNG screenshot from a connected Android device through ADB. "
        "Use this before visual checks, UI debugging, or deciding where to tap."
    )

    async def run(
        self, args: DeviceScreencapArgs, ctx: InvokeContext | None = None
    ) -> AsyncGenerator[ToolStreamEvent | DeviceScreencapResult, None]:
        params = args.model_dump(exclude_none=True)
        response = await self._call_device("device/screencap", params)
        data = response.get("data")
        serial = response.get("serial")
        mime_type = response.get("mimeType") or response.get("mime_type") or "image/png"
        if not isinstance(data, str) or not data:
            raise ToolError("device/screencap returned no image data")
        if not isinstance(serial, str) or not serial:
            serial = args.serial or "unknown"
        yield DeviceScreencapResult(
            serial=serial,
            mime_type=mime_type,
            data=data,
            message=f"Captured screenshot from {serial}.",
        )

    @classmethod
    def format_call_display(cls, args: DeviceScreencapArgs) -> ToolCallDisplay:
        target = args.serial or "first connected device"
        return ToolCallDisplay(summary=f"device screencap: {target}")

    @classmethod
    def get_result_display(cls, event: ToolResultEvent) -> ToolResultDisplay:
        if isinstance(event.result, DeviceScreencapResult):
            return ToolResultDisplay(success=True, message=event.result.message)
        return ToolResultDisplay(success=False, message=event.error or "Screencap failed")

    @classmethod
    def get_status_text(cls) -> str:
        return "Capturing device screenshot"

    @classmethod
    def tool_result_session_update(cls, event: ToolResultEvent) -> ToolCallProgress | None:
        if not isinstance(event.result, DeviceScreencapResult):
            return ToolCallProgress(
                session_update="tool_call_update",
                tool_call_id=event.tool_call_id,
                status="failed",
                kind=resolve_kind(event.tool_name),
                raw_output=event.error or "Screencap failed",
                field_meta={"tool_name": event.tool_name},
            )
        result = event.result
        raw_output = result.model_dump_json()
        raw_with_image = result.model_dump()
        raw_with_image["data"] = result.data
        raw_with_image["mimeType"] = result.mime_type
        return ToolCallProgress(
            session_update="tool_call_update",
            tool_call_id=event.tool_call_id,
            status="completed",
            kind=resolve_kind(event.tool_name),
            raw_output=__import__("json").dumps(raw_with_image),
            content=[
                ContentToolCallContent(
                    type="content",
                    content=TextContentBlock(type="text", text=result.message),
                )
            ],
            field_meta={"tool_name": event.tool_name},
        )


class DeviceTapArgs(BaseModel):
    x: int = Field(description="Screen x coordinate in physical pixels.")
    y: int = Field(description="Screen y coordinate in physical pixels.")
    serial: str | None = Field(default=None, description="ADB device serial.")


class DeviceTapResult(BaseModel):
    ok: bool
    message: str


class DeviceTap(_DeviceTool, ToolUIData[DeviceTapArgs, DeviceTapResult]):
    description: ClassVar[str] = "Tap an Android device screen at x,y coordinates via ADB."

    async def run(
        self, args: DeviceTapArgs, ctx: InvokeContext | None = None
    ) -> AsyncGenerator[ToolStreamEvent | DeviceTapResult, None]:
        await self._call_device("device/tap", args.model_dump(exclude_none=True))
        yield DeviceTapResult(ok=True, message=f"Tapped {args.x},{args.y}.")

    @classmethod
    def format_call_display(cls, args: DeviceTapArgs) -> ToolCallDisplay:
        return ToolCallDisplay(summary=f"device tap: {args.x},{args.y}")

    @classmethod
    def get_result_display(cls, event: ToolResultEvent) -> ToolResultDisplay:
        if isinstance(event.result, DeviceTapResult):
            return ToolResultDisplay(success=event.result.ok, message=event.result.message)
        return ToolResultDisplay(success=False, message=event.error or "Tap failed")

    @classmethod
    def get_status_text(cls) -> str:
        return "Tapping device"


class DeviceSwipeArgs(BaseModel):
    x1: int
    y1: int
    x2: int
    y2: int
    duration: int = Field(default=300, description="Swipe duration in milliseconds.")
    serial: str | None = Field(default=None, description="ADB device serial.")


class DeviceSwipeResult(BaseModel):
    ok: bool
    message: str


class DeviceSwipe(_DeviceTool, ToolUIData[DeviceSwipeArgs, DeviceSwipeResult]):
    description: ClassVar[str] = "Swipe an Android device screen via ADB."

    async def run(
        self, args: DeviceSwipeArgs, ctx: InvokeContext | None = None
    ) -> AsyncGenerator[ToolStreamEvent | DeviceSwipeResult, None]:
        await self._call_device("device/swipe", args.model_dump(exclude_none=True))
        yield DeviceSwipeResult(
            ok=True,
            message=f"Swiped {args.x1},{args.y1} to {args.x2},{args.y2}.",
        )

    @classmethod
    def format_call_display(cls, args: DeviceSwipeArgs) -> ToolCallDisplay:
        return ToolCallDisplay(summary=f"device swipe: {args.x1},{args.y1} -> {args.x2},{args.y2}")

    @classmethod
    def get_result_display(cls, event: ToolResultEvent) -> ToolResultDisplay:
        if isinstance(event.result, DeviceSwipeResult):
            return ToolResultDisplay(success=event.result.ok, message=event.result.message)
        return ToolResultDisplay(success=False, message=event.error or "Swipe failed")

    @classmethod
    def get_status_text(cls) -> str:
        return "Swiping device"


class DeviceAnalyzeScreenshotArgs(BaseModel):
    question: str = Field(
        description=(
            "What to verify or describe in the current device screenshot. "
            "Be concrete, for example: 'Does the home screen show a Start button?'"
        )
    )
    serial: str | None = Field(default=None, description="ADB device serial.")


class DeviceAnalyzeScreenshotResult(BaseModel):
    serial: str
    answer: str
    mime_type: str = "image/png"
    data: str = Field(exclude=True, description="Base64 PNG payload for UI display.")


class DeviceAnalyzeScreenshot(
    _DeviceTool,
    ToolUIData[DeviceAnalyzeScreenshotArgs, DeviceAnalyzeScreenshotResult],
):
    description: ClassVar[str] = (
        "Capture the current Android screen and ask the configured vision endpoint "
        "to analyze it. Use this for visual assertions when closing a project."
    )

    async def run(
        self, args: DeviceAnalyzeScreenshotArgs, ctx: InvokeContext | None = None
    ) -> AsyncGenerator[ToolStreamEvent | DeviceAnalyzeScreenshotResult, None]:
        params = args.model_dump(exclude_none=True)
        response = await self._call_device("device/analyze_screenshot", params)
        data = response.get("data") or ""
        serial = response.get("serial") or args.serial or "unknown"
        answer = response.get("answer") or response.get("analysis") or ""
        if not isinstance(answer, str) or not answer:
            raise ToolError("device/analyze_screenshot returned no analysis")
        yield DeviceAnalyzeScreenshotResult(
            serial=serial,
            answer=answer,
            data=data if isinstance(data, str) else "",
        )

    @classmethod
    def format_call_display(cls, args: DeviceAnalyzeScreenshotArgs) -> ToolCallDisplay:
        return ToolCallDisplay(summary=f"device visual check: {args.question}")

    @classmethod
    def get_result_display(cls, event: ToolResultEvent) -> ToolResultDisplay:
        if isinstance(event.result, DeviceAnalyzeScreenshotResult):
            return ToolResultDisplay(success=True, message=event.result.answer)
        return ToolResultDisplay(success=False, message=event.error or "Visual analysis failed")

    @classmethod
    def get_status_text(cls) -> str:
        return "Analyzing device screenshot"

    @classmethod
    def tool_result_session_update(cls, event: ToolResultEvent) -> ToolCallProgress | None:
        if not isinstance(event.result, DeviceAnalyzeScreenshotResult):
            return ToolCallProgress(
                session_update="tool_call_update",
                tool_call_id=event.tool_call_id,
                status="failed",
                kind=resolve_kind(event.tool_name),
                raw_output=event.error or "Visual analysis failed",
                field_meta={"tool_name": event.tool_name},
            )
        result = event.result
        raw_with_image = result.model_dump()
        raw_with_image["data"] = result.data
        raw_with_image["mimeType"] = result.mime_type
        return ToolCallProgress(
            session_update="tool_call_update",
            tool_call_id=event.tool_call_id,
            status="completed",
            kind=resolve_kind(event.tool_name),
            raw_output=__import__("json").dumps(raw_with_image),
            content=[
                ContentToolCallContent(
                    type="content",
                    content=TextContentBlock(type="text", text=result.answer),
                )
            ],
            field_meta={"tool_name": event.tool_name},
        )
