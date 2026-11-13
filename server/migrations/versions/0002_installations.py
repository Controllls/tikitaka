"""Add per-installation identities without changing existing users or jobs."""
from alembic import op
import sqlalchemy as sa

revision = "0002_installations"
down_revision = "0001_momo"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "installations",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id"), nullable=False, unique=True),
        sa.Column("secret_hash", sa.String(64), nullable=False),
        sa.Column("created_at", sa.Float(), nullable=False),
        sa.Column("revoked_at", sa.Float(), nullable=True),
    )


def downgrade():
    op.drop_table("installations")
