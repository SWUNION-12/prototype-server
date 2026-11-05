"use client";

import { Banner } from "@astryxdesign/core/Banner";
import { Button } from "@astryxdesign/core/Button";
import { Card } from "@astryxdesign/core/Card";
import { Center } from "@astryxdesign/core/Center";
import { ClickableCard } from "@astryxdesign/core/ClickableCard";
import { EmptyState } from "@astryxdesign/core/EmptyState";
import { Grid, GridSpan } from "@astryxdesign/core/Grid";
import { Heading } from "@astryxdesign/core/Heading";
import { HStack } from "@astryxdesign/core/HStack";
import { Icon } from "@astryxdesign/core/Icon";
import { MetadataList, MetadataListItem } from "@astryxdesign/core/MetadataList";
import { Spinner } from "@astryxdesign/core/Spinner";
import { Text } from "@astryxdesign/core/Text";
import { VStack } from "@astryxdesign/core/VStack";
import { AppHeader } from "@/components/AppHeader";
import { FolderIcon, PlusIcon } from "@/components/icons";
import { CourseBadge } from "@/components/StatusBadge";
import { ApiError, courses } from "@/lib/api";
import { studentCountLabel } from "@/lib/display";
import type { Course } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

function CourseCard({ course }: { course: Course }) {
  return (
    <ClickableCard
      label={`${course.name} 열기`}
      href={`/instructor/course?id=${course.id}`}
      padding={6}
      height="100%"
    >
      <VStack gap={4}>
        <HStack gap={2} vAlign="center">
          <Text type="large" weight="semibold">
            {course.name}
          </Text>
          <Text type="supporting">{studentCountLabel(course.studentCount)}</Text>
        </HStack>
        <MetadataList label={{ position: "start", width: 96 }}>
          <MetadataListItem label="Python">
            <Text type="code">{course.pythonVersion}</Text>
          </MetadataListItem>
          <MetadataListItem label="환경 상태">
            <CourseBadge status={course.status} />
          </MetadataListItem>
        </MetadataList>
        {course.status === "FAILED" && course.errorSummary && (
          <VStack gap={2}>
            <Text type="supporting">오류 요약</Text>
            <pre className="error-summary">{course.errorSummary}</pre>
          </VStack>
        )}
      </VStack>
    </ClickableCard>
  );
}

export default function InstructorHomePage() {
  const { data: list, error } = usePoll(courses.list, 3000, (e) => e instanceof ApiError && e.status === 401);

  return (
    <VStack className="app-root">
      <AppHeader />
      <VStack as="main" className="page" gap={6}>
        <HStack hAlign="between" vAlign="center" wrap="wrap" gap={4}>
          <Heading level={1}>환영합니다</Heading>
          <Button
            variant="primary"
            label="수업 만들기"
            href="/instructor/new"
            icon={<Icon icon={PlusIcon} size="sm" />}
          />
        </HStack>

        {error instanceof ApiError && error.status === 401 && list ? (
          <Banner status="error" title={error.message} />
        ) : null}

        {error != null && !list ? (
          <Banner
            status="error"
            title={error instanceof ApiError ? error.message : "수업 목록을 불러오지 못했습니다."}
          />
        ) : list === null ? (
          <Card>
            <Center minHeight={432}>
              <Spinner label="불러오는 중" />
            </Center>
          </Card>
        ) : list.length === 0 ? (
          <Card>
            <Center minHeight={432}>
              <EmptyState
                icon={<Icon icon={FolderIcon} size="lg" color="secondary" />}
                title="현재 만들어진 수업이 없습니다."
                headingLevel={2}
              />
            </Center>
          </Card>
        ) : (
          <Grid columns={{ minWidth: 320, max: 2 }} gap={4} align="stretch">
            {list.map((course) => (
              <GridSpan key={course.id} rows={course.status === "FAILED" ? 2 : undefined}>
                <CourseCard course={course} />
              </GridSpan>
            ))}
          </Grid>
        )}
      </VStack>
    </VStack>
  );
}
