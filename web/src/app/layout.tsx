import type { Metadata } from "next";
import { Noto_Sans_KR } from "next/font/google";
import type { ReactNode } from "react";
import "./globals.css";
import { SERVICE_NAME } from "@/lib/display";
import { Providers } from "./providers";

const notoSansKr = Noto_Sans_KR({
  weight: ["400", "500", "600", "700"],
  subsets: ["latin"],
  variable: "--font-noto-kr",
  preload: false,
});

export const metadata: Metadata = {
  title: SERVICE_NAME,
  description: "강사가 정한 파이썬 환경을 클라우드와 학생 PC에 같은 버전으로 제공하는 실습 지원 서비스",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko" className={notoSansKr.variable}>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
