#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import site


def replace_once(text: str, old: str, new: str, marker: str, target: Path) -> str:
    if marker in text:
        return text
    if old not in text:
        raise SystemExit(f"mistral-vibe ACP image patch shape changed: {target}")
    return text.replace(old, new, 1)


def main() -> None:
    candidates = [
        Path(p) / "vibe/acp/acp_agent_loop.py"
        for p in site.getsitepackages()
    ]
    target = next((p for p in candidates if p.exists()), None)
    if target is None:
        raise SystemExit("mistral-vibe acp_agent_loop.py not found")

    text = target.read_text()
    text = replace_once(
        text,
        "import asyncio\n",
        "import asyncio\nimport base64\n",
        "import base64",
        target,
    )
    text = replace_once(
        text,
        "import logging\n",
        "import logging\nimport mimetypes\n",
        "import mimetypes",
        target,
    )
    text = replace_once(
        text,
        "import sys\n",
        "import sys\nimport tempfile\n",
        "import tempfile",
        target,
    )
    text = replace_once(
        text,
        "    ToolResultEvent,\n",
        "    ToolResultEvent,\n    ImageAttachment,\n",
        "ImageAttachment",
        target,
    )

    helper = '''    def _extract_image_attachments(
        self, acp_prompt: list[ContentBlock], session_id: str, message_id: str
    ) -> list[ImageAttachment]:
        attachments: list[ImageAttachment] = []
        for index, block in enumerate(acp_prompt, start=1):
            if block.type != "image":
                continue

            mime_type = getattr(block, "mime_type", None) or "image/png"
            extension = mimetypes.guess_extension(mime_type) or ".png"
            if extension == ".jpe":
                extension = ".jpg"
            safe_message_id = "".join(
                c if c.isalnum() or c in ("-", "_") else "_" for c in message_id
            )
            attachment_dir = Path(tempfile.gettempdir()) / "vibe-acp-images" / session_id
            attachment_dir.mkdir(parents=True, exist_ok=True)
            path = attachment_dir / f"{safe_message_id}-{index}{extension}"
            try:
                path.write_bytes(base64.b64decode(block.data, validate=True))
            except Exception as e:
                raise InvalidRequestError(f"Invalid image content block: {e}") from e

            attachments.append(
                ImageAttachment(
                    path=path.resolve(),
                    alias=getattr(block, "uri", None) or path.name,
                    mime_type=mime_type,
                )
            )
        return attachments

'''
    text = replace_once(
        text,
        "    def _build_end_turn_meta(self, session: AcpSessionLoop) -> dict[str, Any] | None:\n",
        helper + "    def _build_end_turn_meta(self, session: AcpSessionLoop) -> dict[str, Any] | None:\n",
        "_extract_image_attachments",
        target,
    )
    text = replace_once(
        text,
        '''        text_prompt = ""
        for block in ordered:
            separator = "\\n\\n" if text_prompt else ""
            match block.type:
                # NOTE: ACP supports annotations, but we don't use them here yet.
                case "text":
''',
        '''        text_prompt = ""
        for block in ordered:
            separator = "\\n\\n" if text_prompt else ""
            match block.type:
                case "image":
                    continue
                # NOTE: ACP supports annotations, but we don't use them here yet.
                case "text":
''',
        'case "image":\n                    continue',
        target,
    )
    text = replace_once(
        text,
        '''        text_prompt = self._build_text_prompt(prompt)
        resolved_message_id = _resolved_user_message_id(message_id)
''',
        '''        text_prompt = self._build_text_prompt(prompt)
        resolved_message_id = _resolved_user_message_id(message_id)
        image_attachments = self._extract_image_attachments(
            prompt, session.id, resolved_message_id
        )
''',
        "image_attachments = self._extract_image_attachments",
        target,
    )
    text = replace_once(
        text,
        '''                session, text_prompt, resolved_message_id, auto_title=auto_title
''',
        '''                session,
                text_prompt,
                resolved_message_id,
                auto_title=auto_title,
                images=image_attachments,
''',
        "images=image_attachments",
        target,
    )
    text = replace_once(
        text,
        '''        auto_title: str | None = None,
    ) -> AsyncGenerator[SessionUpdate | UsageUpdate]:
''',
        '''        auto_title: str | None = None,
        images: list[ImageAttachment] | None = None,
    ) -> AsyncGenerator[SessionUpdate | UsageUpdate]:
''',
        "images: list[ImageAttachment] | None = None",
        target,
    )
    text = replace_once(
        text,
        '''                auto_title=auto_title,
            )
''',
        '''                auto_title=auto_title,
                images=images,
            )
''',
        "images=images",
        target,
    )

    target.write_text(text)
    print(f"patched mistral-vibe ACP image blocks: {target}")


if __name__ == "__main__":
    main()
