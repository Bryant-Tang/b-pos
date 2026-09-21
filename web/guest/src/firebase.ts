/**
 * Firebase 初始化。
 *
 * **設定值不寫在程式碼裡，執行時跟 Hosting 要。** Firebase Hosting 會在
 * `/__/firebase/init.json` 自動提供它所屬專案的 web 設定，不必自己填 apiKey 與
 * projectId。這正好解掉 CLAUDE.md 第一節的限制：真實的 Firebase 專案 ID 不能進版控，
 * 而這個 repo 是公開的。順帶好處是之後換到店家的 Firebase 專案時，
 * 這支程式一個字都不用改，部署到哪個專案就自動連哪個。
 *
 * 本機 `npm run dev` 沒有 Hosting，所以退回讀 `.env.local` 的 VITE_ 變數
 * （見 .env.example）。那份檔案在 .gitignore 裡。
 */

import { initializeApp, type FirebaseApp, type FirebaseOptions } from 'firebase/app';
import { getAuth, signInAnonymously, type Auth } from 'firebase/auth';
import { getFunctions, type Functions } from 'firebase/functions';

/** 與 Cloud Functions 一致（SPEC 第五節）。呼叫端指錯區域會連不上。 */
const REGION = 'asia-east1';

async function loadOptions(): Promise<FirebaseOptions> {
  try {
    const res = await fetch('/__/firebase/init.json');
    if (res.ok) return (await res.json()) as FirebaseOptions;
  } catch {
    // 本機開發時這個路徑不存在，往下走環境變數。
  }

  const env = import.meta.env;
  const apiKey = env['VITE_FIREBASE_API_KEY'];
  const projectId = env['VITE_FIREBASE_PROJECT_ID'];
  if (typeof apiKey !== 'string' || typeof projectId !== 'string') {
    throw new Error(
      '讀不到 Firebase 設定。部署在 Firebase Hosting 上會自動有 /__/firebase/init.json；' +
        '本機開發請照 .env.example 建一份 .env.local。',
    );
  }
  return {
    apiKey,
    projectId,
    authDomain: env['VITE_FIREBASE_AUTH_DOMAIN'] ?? `${projectId}.firebaseapp.com`,
    appId: env['VITE_FIREBASE_APP_ID'],
  };
}

export interface Services {
  app: FirebaseApp;
  auth: Auth;
  functions: Functions;
  uid: string;
}

let services: Promise<Services> | undefined;

/**
 * 連上 Firebase 並拿到一個匿名身分。
 *
 * 匿名登入是**技術上的識別碼，不是帳號**：SDK 在背景靜默取得，客人完全無感，
 * 畫面上不會有任何註冊或登入介面（SPEC 第十三節〈顧客端不得有登入介面〉）。
 */
export function connect(): Promise<Services> {
  services ??= (async () => {
    const app = initializeApp(await loadOptions());
    const auth = getAuth(app);
    const credential = await signInAnonymously(auth);
    return {
      app,
      auth,
      functions: getFunctions(app, REGION),
      uid: credential.user.uid,
    };
  })();
  return services;
}
