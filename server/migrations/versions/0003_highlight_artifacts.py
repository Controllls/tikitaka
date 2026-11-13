"""Persist authenticated highlight files under the existing leased job lifecycle."""
from alembic import op
import sqlalchemy as sa

revision = "0003_highlight_artifacts"
down_revision = "0002_installations"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("job_artifacts",
        sa.Column("job_id", sa.String(36), sa.ForeignKey("analysis_jobs.id"), primary_key=True),
        sa.Column("name", sa.String(64), primary_key=True),
        sa.Column("storage_key", sa.String(256), nullable=False, unique=True),
        sa.Column("sha256", sa.String(64), nullable=False),
        sa.Column("size", sa.Integer(), nullable=False))


def downgrade():
    op.drop_table("job_artifacts")
