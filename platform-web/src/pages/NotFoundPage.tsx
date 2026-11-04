import { Button, Result } from 'antd';
import { useNavigate } from 'react-router-dom';

export function NotFoundPage() {
  const navigate = useNavigate();
  return (
    <Result
      status="404"
      title="页面不存在"
      subTitle="链接可能已失效，或者该功能尚未开放。"
      extra={
        <Button type="primary" onClick={() => navigate('/query')}>
          回到查询工作台
        </Button>
      }
    />
  );
}
